package io.pure.yaml;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.pure.yaml.core.Yaml;
import io.pure.yaml.core.YamlException;
import io.pure.yaml.core.model.YamlAlias;
import io.pure.yaml.core.model.YamlDocument;
import io.pure.yaml.core.model.YamlEntry;
import io.pure.yaml.core.model.YamlMapping;
import io.pure.yaml.core.model.YamlNode;
import io.pure.yaml.core.model.YamlScalar;
import io.pure.yaml.core.model.YamlScalarStyle;
import io.pure.yaml.core.model.YamlSequence;
import io.pure.yaml.core.model.YamlStream;
import io.pure.yaml.core.model.YamlTags;
import io.pure.yaml.core.parser.YamlProcessingLimits;

/**
 * Runs the official yaml-test-suite (https://github.com/yaml/yaml-test-suite, branch "data").
 * The suite location is taken from the YAML_TEST_SUITE_DIR environment variable or the
 * yaml.test.suite.dir system property; without it the test is skipped.
 *
 * <p>For every valid case that is parsed, the test compares the representation with the expected
 * events (anchors, tags, scalar styles and values) and with the expected JSON, and checks that the
 * stream survives a round trip through the writer.
 */
class YamlTestSuiteTest {
    private static final YamlProcessingLimits LIMITS = new YamlProcessingLimits(64, 64, 16, 1_000_000);

    @Test
    void conformsToYamlTestSuite() throws IOException {
        String location = System.getenv().getOrDefault("YAML_TEST_SUITE_DIR", System.getProperty("yaml.test.suite.dir", ""));
        assumeTrue(!location.isBlank(), "YAML_TEST_SUITE_DIR is not set");
        Path root = Path.of(location);
        assumeTrue(Files.isDirectory(root), "yaml-test-suite not found at " + root);

        Set<String> unsupported = loadUnsupportedCases();
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Path directory : caseDirectories(root)) {
            String id = root.relativize(directory).toString().replace('\\', '/');
            boolean expectError = Files.exists(directory.resolve("error"));
            checked++;

            YamlStream stream;
            try {
                // Bytes, so that encoding detection is exercised as well.
                stream = Yaml.parseStream(Files.readAllBytes(directory.resolve("in.yaml")), LIMITS);
            } catch (YamlException exception) {
                if (!expectError && !unsupported.remove(id)) {
                    problems.add(id + ": valid YAML rejected: " + exception.getMessage());
                }
                continue;
            } catch (RuntimeException | StackOverflowError unexpected) {
                problems.add(id + ": unexpected " + unexpected);
                continue;
            }

            if (expectError) {
                problems.add(id + ": invalid YAML accepted");
                continue;
            }
            if (unsupported.remove(id)) {
                problems.add(id + ": listed as unsupported but parsed successfully, remove it from unsupported-cases.txt");
                continue;
            }
            if (Files.exists(directory.resolve("test.event"))) {
                List<String> expected = expectedEvents(Files.readString(directory.resolve("test.event")));
                List<String> actual = events(stream, true);
                if (!expected.equals(actual)) {
                    problems.add(id + ": events differ\n    expected: " + expected + "\n    actual:   " + actual);
                }
            }
            if (Files.exists(directory.resolve("in.json"))) {
                List<Object> expected = Json.parseAll(Files.readString(directory.resolve("in.json")));
                List<YamlDocument> documents = stream.documents();
                boolean matches = expected.size() == documents.size();
                for (int i = 0; matches && i < expected.size(); i++) {
                    matches = matchesJson(documents.get(i).root(), expected.get(i));
                }
                if (!matches) {
                    problems.add(id + ": parsed tree differs from in.json\n    expected: " + expected + "\n    actual:   " + events(stream, true));
                }
            }
            checkWriterRoundTrip(id, stream, problems);
        }
        unsupported.forEach(id -> problems.add(id + ": listed in unsupported-cases.txt but missing from the suite"));

        assertTrue(checked > 300, "Too few yaml-test-suite cases found in " + root + ": " + checked);
        assertTrue(problems.isEmpty(), () -> problems.size() + " yaml-test-suite problems:\n" + String.join("\n", problems));
    }

    /**
     * Every parsed stream must be written and read back to the same events; scalar styles may change.
     */
    private static void checkWriterRoundTrip(String id, YamlStream stream, List<String> problems) {
        String written;
        try {
            written = Yaml.stringify(stream);
        } catch (YamlException exception) {
            problems.add(id + ": parsed stream cannot be written: " + exception.getMessage());
            return;
        }
        try {
            List<String> expected = events(stream, false);
            List<String> actual = events(Yaml.parseStream(written, LIMITS), false);
            if (!expected.equals(actual)) {
                problems.add(id + ": writer round trip differs\n    expected: " + expected + "\n    actual:   " + actual);
            }
        } catch (YamlException exception) {
            problems.add(id + ": written stream cannot be parsed: " + exception.getMessage() + "\n    " + written);
        }
    }

    /**
     * Node events of test.event; stream and document events and flow style markers are not compared.
     */
    private static List<String> expectedEvents(String text) {
        return text.lines()
            .filter(line -> !line.isBlank() && !line.equals("+STR") && !line.equals("-STR"))
            .filter(line -> !line.startsWith("+DOC") && !line.startsWith("-DOC"))
            .map(line -> line.replaceFirst("^([+]MAP|[+]SEQ) (\\{\\}|\\[\\])", "$1"))
            .toList();
    }

    private static List<String> events(YamlStream stream, boolean withStyle) {
        List<String> events = new ArrayList<>();
        stream.documents().forEach(document -> addEvents(document.root(), events, withStyle));
        return events;
    }

    private static void addEvents(YamlNode node, List<String> events, boolean withStyle) {
        switch (node) {
            case YamlMapping mapping -> {
                events.add("+MAP" + properties(mapping));
                for (YamlEntry entry : mapping.entries()) {
                    addEvents(entry.key(), events, withStyle);
                    addEvents(entry.value(), events, withStyle);
                }
                events.add("-MAP");
            }
            case YamlSequence sequence -> {
                events.add("+SEQ" + properties(sequence));
                sequence.values().forEach(item -> addEvents(item, events, withStyle));
                events.add("-SEQ");
            }
            case YamlScalar scalar -> events.add("=VAL" + properties(scalar) + " "
                + (withStyle ? styleIndicator(scalar.style()) : '=') + escape(scalar.value() == null ? "" : scalar.value()));
            case YamlAlias alias -> events.add("=ALI *" + alias.alias());
        }
    }

    private static String properties(YamlNode node) {
        return (node.anchor() == null ? "" : " &" + node.anchor()) + (node.tag() == null ? "" : " <" + node.tag() + ">");
    }

    private static char styleIndicator(YamlScalarStyle style) {
        return switch (style) {
            case PLAIN -> ':';
            case SINGLE_QUOTED -> '\'';
            case DOUBLE_QUOTED -> '"';
            case LITERAL -> '|';
            case FOLDED -> '>';
        };
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\b", "\\b").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * Compares a node with the JSON form of the suite, which applies the core schema: numbers are
     * compared by value and objects regardless of key order.
     */
    private static boolean matchesJson(YamlNode node, Object json) {
        YamlNode resolved = node instanceof YamlAlias alias ? alias.target() : node;
        if (json instanceof Map<?, ?> object) {
            if (!(resolved instanceof YamlMapping mapping) || mapping.size() != object.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : object.entrySet()) {
                YamlEntry match = mapping.entries().stream()
                    .filter(candidate -> entry.getKey().equals(text(candidate.key())))
                    .findFirst()
                    .orElse(null);
                if (match == null || !matchesJson(match.value(), entry.getValue())) {
                    return false;
                }
            }
            return true;
        }
        if (json instanceof List<?> array) {
            if (!(resolved instanceof YamlSequence sequence) || sequence.size() != array.size()) {
                return false;
            }
            for (int i = 0; i < array.size(); i++) {
                if (!matchesJson(sequence.get(i), array.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (!(resolved instanceof YamlScalar scalar)) {
            return false;
        }
        boolean textual = YamlTags.STR.equals(scalar.tag()) || YamlTags.NON_SPECIFIC.equals(scalar.tag());
        if (json == null) {
            return !textual && (scalar.value() == null || scalar.value().matches("null|Null|NULL|~"));
        }
        if (scalar.value() == null && !textual) {
            return false;
        }
        String text = scalar.value() == null ? "" : scalar.value();
        if (json instanceof Boolean bool) {
            return text.equalsIgnoreCase(bool.toString());
        }
        if (json instanceof BigDecimal number) {
            BigDecimal value = parseNumber(text);
            return value != null && value.compareTo(number) == 0;
        }
        return json.equals(text);
    }

    private static String text(YamlNode key) {
        YamlNode resolved = key instanceof YamlAlias alias ? alias.target() : key;
        String value = resolved instanceof YamlScalar scalar ? scalar.value() : null;
        return value == null ? "" : value;
    }

    private static BigDecimal parseNumber(String text) {
        try {
            if (text.startsWith("0x")) {
                return new BigDecimal(new BigInteger(text.substring(2), 16));
            }
            if (text.startsWith("0o")) {
                return new BigDecimal(new BigInteger(text.substring(2), 8));
            }
            return new BigDecimal(text);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static List<Path> caseDirectories(Path root) throws IOException {
        List<Path> result = new ArrayList<>();
        try (Stream<Path> entries = Files.list(root)) {
            for (Path entry : entries.sorted().toList()) {
                String name = entry.getFileName().toString();
                // "name" and "tags" are symlink indexes of the same cases.
                if (!Files.isDirectory(entry) || name.startsWith(".") || name.equals("name") || name.equals("tags")) {
                    continue;
                }
                if (Files.exists(entry.resolve("in.yaml"))) {
                    result.add(entry);
                    continue;
                }
                try (Stream<Path> variants = Files.list(entry)) {
                    variants.sorted().filter(variant -> Files.exists(variant.resolve("in.yaml"))).forEach(result::add);
                }
            }
        }
        return result;
    }

    private static Set<String> loadUnsupportedCases() throws IOException {
        try (InputStream input = YamlTestSuiteTest.class.getResourceAsStream("/yaml-test-suite/unsupported-cases.txt")) {
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return content.lines()
                .map(line -> line.replaceAll("#.*", "").strip())
                .filter(line -> !line.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    /**
     * Minimal reader for the JSON documents of the suite: objects, arrays, strings, numbers
     * (as {@link BigDecimal}), booleans and null.
     */
    private static final class Json {
        private final String text;
        private int position;

        private Json(String text) {
            this.text = text;
        }

        static List<Object> parseAll(String text) {
            Json json = new Json(text);
            List<Object> documents = new ArrayList<>();
            while (true) {
                json.skipWhitespace();
                if (json.position >= text.length()) {
                    return documents;
                }
                documents.add(json.readValue());
            }
        }

        private Object readValue() {
            skipWhitespace();
            char ch = text.charAt(position);
            if (ch == '{') {
                position++;
                Map<String, Object> object = new LinkedHashMap<>();
                skipWhitespace();
                while (text.charAt(position) != '}') {
                    String key = (String) readValue();
                    skipWhitespace();
                    position++;
                    object.put(key, readValue());
                    skipSeparator();
                }
                position++;
                return object;
            }
            if (ch == '[') {
                position++;
                List<Object> array = new ArrayList<>();
                skipWhitespace();
                while (text.charAt(position) != ']') {
                    array.add(readValue());
                    skipSeparator();
                }
                position++;
                return array;
            }
            if (ch == '"') {
                return readString();
            }
            int start = position;
            while (position < text.length() && ",]}".indexOf(text.charAt(position)) < 0 && !Character.isWhitespace(text.charAt(position))) {
                position++;
            }
            String literal = text.substring(start, position);
            return switch (literal) {
                case "null" -> null;
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                default -> new BigDecimal(literal);
            };
        }

        private String readString() {
            StringBuilder builder = new StringBuilder();
            position++;
            while (text.charAt(position) != '"') {
                char ch = text.charAt(position++);
                if (ch != '\\') {
                    builder.append(ch);
                    continue;
                }
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case 'n' -> builder.append('\n');
                    case 't' -> builder.append('\t');
                    case 'r' -> builder.append('\r');
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'u' -> {
                        builder.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                        position += 4;
                    }
                    default -> builder.append(escaped);
                }
            }
            position++;
            return builder.toString();
        }

        private void skipSeparator() {
            skipWhitespace();
            if (text.charAt(position) == ',') {
                position++;
            }
            skipWhitespace();
        }

        private void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }
    }
}
