package bench;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.yaml.snakeyaml.Yaml;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import io.pure.yaml.binding.mapper.YamlMapper;

/**
 * Runs one edge case with one library, using the library's default settings (except for
 * {@code aliases_10000_enabled}), and prints {@code RESULT|outcome|milliseconds|detail}, where the
 * outcome is {@code accepted}, {@code rejected} (an exception) or {@code crashed} (an error such as
 * StackOverflowError or OutOfMemoryError). Meant to be run in its own JVM (see {@link EdgeRunner}).
 */
public final class EdgeCases {
    public static final List<String> CASES = List.of(
        "billion_laughs",
        "deep_flow_10000",
        "deep_block_1200",
        "json_single_line_20mb",
        "block_document_20mb",
        "plain_scalar_10mb",
        "huge_number_1e1000000000",
        "duplicate_keys",
        "unknown_tag_object",
        "utf16_bytes",
        "aliases_10000",
        "aliases_10000_enabled",
        "invalid_tab_indent",
        "empty_document"
    );

    private EdgeCases() {
    }

    public static void main(String[] args) {
        String caseName = args[1];
        YamlLib lib = YamlLib.of(args[0], caseName.equals("aliases_10000_enabled") ? YamlLib.Mode.ALIASES_ENABLED : YamlLib.Mode.DEFAULTS);
        String outcome;
        String detail;
        long start = System.nanoTime();
        try {
            detail = run(lib, caseName);
            outcome = "accepted";
        } catch (Exception exception) {
            outcome = "rejected";
            detail = exception.getClass().getSimpleName() + ": " + abbreviate(String.valueOf(exception.getMessage()));
        } catch (Error error) {
            outcome = "crashed";
            detail = error.getClass().getSimpleName();
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("RESULT|" + outcome + "|" + millis + "|" + detail);
        System.out.flush();
    }

    private static String run(YamlLib lib, String caseName) {
        return switch (caseName) {
            case "billion_laughs" -> describe(lib.parseUntyped(Data.billionLaughs(9)));
            case "deep_flow_10000" -> describe(lib.parseUntyped(Data.deepFlow(10_000)));
            case "deep_block_1200" -> describe(lib.parseUntyped(Data.deepBlock(1_200)));
            case "json_single_line_20mb" -> describe(lib.parseUntyped(Data.recordsJson(200_000)));
            case "block_document_20mb" -> describe(lib.parseUntyped(Data.records(200_000)));
            case "plain_scalar_10mb" -> describe(lib.parseUntyped("value: " + "x".repeat(10_000_000)));
            case "huge_number_1e1000000000" -> describe(lib.parseUntyped("value: 1e1000000000"));
            case "duplicate_keys" -> describe(lib.parseUntyped("a: 1\nb: 2\na: 3\n"));
            case "unknown_tag_object" -> describe(lib.parseUntyped("!!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://127.0.0.1:1/\"]]]]"));
            case "utf16_bytes" -> utf16(lib);
            case "aliases_10000", "aliases_10000_enabled" -> describe(lib.parseUntyped(Data.anchors(10_000)));
            case "invalid_tab_indent" -> describe(lib.parseUntyped("a:\n\tb: 1\n"));
            case "empty_document" -> describe(lib.parseUntyped(""));
            default -> throw new IllegalArgumentException(caseName);
        };
    }

    private static String utf16(YamlLib lib) {
        byte[] bytes = ("\uFEFF" + "name: Ann\nage: 30\n").getBytes(StandardCharsets.UTF_16LE);
        Object value = switch (lib.name()) {
            case "pure" -> YamlMapper.createDefault().readValue(bytes, Object.class);
            case "snakeyaml" -> new Yaml().load(new java.io.ByteArrayInputStream(bytes));
            case "engine" -> new Load(LoadSettings.builder().build()).loadFromInputStream(new java.io.ByteArrayInputStream(bytes));
            default -> {
                try {
                    yield new YAMLMapper().readValue(bytes, Object.class);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }
        };
        return describe(value);
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            return "map(" + map.size() + ") " + abbreviate(String.valueOf(map));
        }
        if (value instanceof List<?> list) {
            return "list(" + list.size() + ") " + abbreviate(describeNesting(list));
        }
        return value.getClass().getSimpleName() + " " + abbreviate(String.valueOf(value));
    }

    private static String describeNesting(Object value) {
        int depth = 0;
        Object current = value;
        while (current instanceof List<?> list && !list.isEmpty()) {
            depth++;
            current = list.getFirst();
        }
        return "depth " + depth + ", leaf " + current;
    }

    private static String abbreviate(String text) {
        String single = text.replace("\n", "\\n");
        return single.length() <= 80 ? single : single.substring(0, 80) + "...";
    }
}
