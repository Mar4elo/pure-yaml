package bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.schema.CoreSchema;

import io.pure.yaml.binding.mapper.YamlMapper;
import io.pure.yaml.core.Yaml;
import io.pure.yaml.core.parser.YamlProcessingLimits;

/**
 * Checks that every library parses every real-world document in benchmark mode, and that this
 * library and snakeyaml-engine (both YAML 1.2 with the core schema) produce equal maps and lists.
 */
public final class Validate {
    private Validate() {
    }

    public static void main(String[] args) {
        boolean failed = false;
        for (String document : Data.REAL_DOCUMENTS) {
            String text = Data.real(document);
            List<String> counts = new ArrayList<>();
            for (String name : YamlLib.NAMES) {
                YamlLib lib = YamlLib.of(name, YamlLib.Mode.BENCHMARK);
                try {
                    counts.add(name + "=" + lib.parseTreeAll(text) + "/" + lib.parseUntypedAll(text));
                } catch (RuntimeException exception) {
                    counts.add(name + " FAILED: " + exception);
                    failed = true;
                }
            }
            String equality = compare(text);
            failed |= !equality.equals("equal");
            System.out.println(document + ": " + String.join(", ", counts) + "; pure vs engine: " + equality);
        }
        if (failed) {
            System.exit(1);
        }
    }

    private static String compare(String text) {
        YamlProcessingLimits limits = new YamlProcessingLimits(10_000, 1_000_000, 100_000, 200_000_000, 1_000, 100_000_000);
        YamlMapper mapper = YamlMapper.builder().processingLimits(limits).resolveAliases(true).build();
        List<Object> pure = new ArrayList<>();
        for (var document : Yaml.parseStream(text, limits).documents()) {
            pure.add(mapper.readValue(document.root(), Object.class));
        }
        List<Object> engine = new ArrayList<>();
        LoadSettings settings = LoadSettings.builder().setCodePointLimit(200_000_000).setSchema(new CoreSchema()).build();
        new Load(settings).loadAllFromString(text).forEach(engine::add);
        if (pure.size() != engine.size()) {
            return "document count " + pure.size() + " vs " + engine.size();
        }
        for (int i = 0; i < pure.size(); i++) {
            String difference = difference(pure.get(i), engine.get(i), "doc" + i);
            if (difference != null) {
                return "differs at " + difference;
            }
        }
        return "equal";
    }

    private static String difference(Object a, Object b, String path) {
        if (a instanceof java.util.Map<?, ?> left && b instanceof java.util.Map<?, ?> right) {
            if (!left.keySet().equals(right.keySet())) {
                return path + " keys";
            }
            for (var entry : left.entrySet()) {
                String difference = difference(entry.getValue(), right.get(entry.getKey()), path + "." + entry.getKey());
                if (difference != null) {
                    return difference;
                }
            }
            return null;
        }
        if (a instanceof List<?> left && b instanceof List<?> right) {
            if (left.size() != right.size()) {
                return path + " size";
            }
            for (int i = 0; i < left.size(); i++) {
                String difference = difference(left.get(i), right.get(i), path + "[" + i + "]");
                if (difference != null) {
                    return difference;
                }
            }
            return null;
        }
        if (Objects.equals(a, b)) {
            return null;
        }
        return path + ": " + describe(a) + " vs " + describe(b);
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName() + " " + value;
    }
}
