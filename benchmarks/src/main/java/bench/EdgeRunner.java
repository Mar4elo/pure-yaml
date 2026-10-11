package bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs every edge case with every library in a separate JVM with a memory and time limit, and
 * prints a Markdown table. Also measures cold start (the wall time from JVM start until a small
 * configuration has been parsed) with the default JIT and with C1 only, and the size of the
 * written output.
 */
public final class EdgeRunner {
    private static final List<String> LIBS = YamlLib.NAMES;
    private static final List<String> WRITERS = List.of("pure", "snakeyaml", "jackson");
    private static final int TIMEOUT_SECONDS = 30;
    private static final int COLD_START_RUNS = 7;

    private EdgeRunner() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("## Edge cases (default settings, -Xmx512m, timeout " + TIMEOUT_SECONDS + " s)\n");
        System.out.println("| Case | " + String.join(" | ", LIBS) + " |");
        System.out.println("|---|" + "---|".repeat(LIBS.size()));
        for (String caseName : EdgeCases.CASES) {
            List<String> cells = new ArrayList<>();
            for (String lib : LIBS) {
                cells.add(runCase(lib, caseName));
            }
            System.out.println("| " + caseName + " | " + String.join(" | ", cells) + " |");
        }

        System.out.println("\n## Cold start (JVM start to first parsed document, median of " + COLD_START_RUNS + " runs, ms)\n");
        System.out.println("| Library | default JIT | -XX:TieredStopAtLevel=1 |");
        System.out.println("|---|---|---|");
        for (String lib : LIBS) {
            System.out.println("| " + lib + " | " + coldStartMedian(lib, List.of()) + " | "
                + coldStartMedian(lib, List.of("-XX:TieredStopAtLevel=1")) + " |");
        }

        System.out.println("\n## Written output (default settings)\n");
        System.out.println("| Library | 500 records, bytes | 500 records, lines | 50 000 records, bytes |");
        System.out.println("|---|---|---|---|");
        for (String lib : WRITERS) {
            YamlLib writer = YamlLib.of(lib, YamlLib.Mode.DEFAULTS);
            String medium = writer.write(Data.recordObjects(500));
            String large = writer.write(Data.recordObjects(50_000));
            System.out.println("| " + lib + " | " + medium.getBytes(StandardCharsets.UTF_8).length + " | "
                + medium.lines().count() + " | " + large.getBytes(StandardCharsets.UTF_8).length + " |");
        }
    }

    private static long coldStartMedian(String lib, List<String> jvmArgs) throws Exception {
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < COLD_START_RUNS; i++) {
            times.add(coldStart(lib, jvmArgs));
        }
        times.sort(null);
        return times.get(times.size() / 2);
    }

    private static String runCase(String lib, String caseName) throws Exception {
        Result result = run(List.of("-Xmx512m", "-Xss2m", "-cp", classpath(), EdgeCases.class.getName(), lib, caseName));
        if (result.timedOut()) {
            return "**timeout** >" + TIMEOUT_SECONDS + " s";
        }
        String line = result.output().lines().filter(l -> l.startsWith("RESULT|")).findFirst().orElse(null);
        if (line == null) {
            String tail = result.output().lines().filter(l -> l.contains("Error") || l.contains("Exception")).findFirst().orElse("exit " + result.exitCode());
            return "**crash** " + escape(tail);
        }
        String[] parts = line.split("\\|", 4);
        return parts[1] + ", " + parts[2] + " ms: " + escape(parts[3]);
    }

    private static long coldStart(String lib, List<String> jvmArgs) throws Exception {
        List<String> args = new ArrayList<>(jvmArgs);
        args.addAll(List.of("-Xshare:auto", "-cp", classpath(), ColdStart.class.getName(), lib));
        Result result = run(args);
        String line = result.output().lines().filter(l -> l.startsWith("COLD|")).findFirst()
            .orElseThrow(() -> new IllegalStateException("No cold start result for " + lib + ":\n" + result.output()));
        return Long.parseLong(line.split("\\|")[1]);
    }

    private static Result run(List<String> javaArgs) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(javaArgs);
        Path output = Files.createTempFile("edge", ".txt");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor();
        }
        String text = Files.readString(output, StandardCharsets.UTF_8);
        Files.delete(output);
        return new Result(!finished, process.exitValue(), text);
    }

    private static String classpath() throws IOException {
        return System.getProperty("java.class.path");
    }

    private static String escape(String text) {
        return text.replace("|", "\\|");
    }

    private record Result(boolean timedOut, int exitCode, String output) {
    }
}
