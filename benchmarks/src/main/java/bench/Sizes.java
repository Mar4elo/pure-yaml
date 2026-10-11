package bench;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prints the size of every benchmark input as a Markdown table.
 */
public final class Sizes {
    private Sizes() {
    }

    public static void main(String[] args) {
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("small configuration", Data.smallConfig());
        inputs.put("500 records", Data.records(500));
        inputs.put("50 000 records", Data.records(50_000));
        inputs.put("10 000 records, single-line JSON", Data.recordsJson(10_000));
        inputs.put("10 000 aliases", Data.anchors(10_000));
        inputs.put("2 000 block scalars", Data.blockScalars(2_000));
        inputs.put("1 000 documents", Data.multiDocument(1_000));
        for (String name : Data.REAL_DOCUMENTS) {
            inputs.put(name, Data.real(name));
        }
        System.out.println("| Input | bytes | lines | documents | comment lines |");
        System.out.println("|---|---|---|---|---|");
        YamlLib pure = YamlLib.of("pure", YamlLib.Mode.BENCHMARK);
        inputs.forEach((name, text) -> System.out.println("| " + name
            + " | " + text.getBytes(StandardCharsets.UTF_8).length
            + " | " + text.lines().count()
            + " | " + pure.parseTreeAll(text)
            + " | " + text.lines().filter(line -> line.stripLeading().startsWith("#")).count() + " |"));
    }
}
