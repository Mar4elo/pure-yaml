package io.pure.yaml;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.pure.yaml.core.Yaml;
import io.pure.yaml.core.YamlException;
import io.pure.yaml.core.model.YamlAlias;
import io.pure.yaml.core.model.YamlDocument;
import io.pure.yaml.core.model.YamlMapping;
import io.pure.yaml.core.model.YamlNode;
import io.pure.yaml.core.model.YamlScalar;
import io.pure.yaml.core.model.YamlSequence;
import io.pure.yaml.core.model.YamlStream;
import io.pure.yaml.core.parser.YamlProcessingLimits;

/**
 * Runs the official yaml-test-suite (https://github.com/yaml/yaml-test-suite, branch "data").
 * The suite location is taken from the YAML_TEST_SUITE_DIR environment variable or the
 * yaml.test.suite.dir system property; without it the test is skipped.
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
            String yaml = Files.readString(directory.resolve("in.yaml"));
            boolean expectError = Files.exists(directory.resolve("error"));
            checked++;

            YamlStream stream;
            try {
                stream = Yaml.parseStream(yaml, LIMITS);
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
            } else if (unsupported.remove(id)) {
                problems.add(id + ": listed as unsupported but parsed successfully, remove it from unsupported-cases.txt");
            } else if (Files.exists(directory.resolve("in.json"))) {
                String expected = Json.normalize(Files.readString(directory.resolve("in.json")));
                String actual = stream.documents().stream().map(YamlDocument::root).map(YamlTestSuiteTest::render)
                    .collect(Collectors.joining("\n"));
                if (!expected.equals(actual)) {
                    problems.add(id + ": parsed tree differs from in.json\n    expected: " + expected + "\n    actual:   " + actual);
                }
            }
        }
        unsupported.forEach(id -> problems.add(id + ": listed in unsupported-cases.txt but missing from the suite"));

        assertTrue(checked > 300, "Too few yaml-test-suite cases found in " + root + ": " + checked);
        assertTrue(problems.isEmpty(), () -> problems.size() + " yaml-test-suite problems:\n" + String.join("\n", problems));
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
     * Renders a tree as compact JSON where every scalar is a string, so that the structure
     * can be compared with in.json without applying a schema.
     */
    private static String render(YamlNode node) {
        return switch (node) {
            case YamlScalar scalar -> scalar.value() == null ? "null" : Json.quote(scalar.value());
            case YamlSequence sequence -> sequence.values().stream().map(YamlTestSuiteTest::render)
                .collect(Collectors.joining(",", "[", "]"));
            case YamlMapping mapping -> mapping.entries().stream()
                .map(entry -> render(entry.key()) + ":" + render(entry.value()))
                .collect(Collectors.joining(",", "{", "}"));
            case YamlAlias alias -> "*" + alias.alias();
        };
    }

    /**
     * Minimal reader for the JSON documents of the suite; renders them like {@link #render(YamlNode)}.
     */
    private static final class Json {
        private final String text;
        private int position;

        private Json(String text) {
            this.text = text;
        }

        static String normalize(String text) {
            Json json = new Json(text);
            List<String> documents = new ArrayList<>();
            while (true) {
                json.skipWhitespace();
                if (json.position >= text.length()) {
                    return String.join("\n", documents);
                }
                documents.add(json.readValue());
            }
        }

        static String quote(String value) {
            StringBuilder builder = new StringBuilder("\"");
            value.codePoints().forEach(codePoint -> {
                switch (codePoint) {
                    case '"' -> builder.append("\\\"");
                    case '\\' -> builder.append("\\\\");
                    default -> {
                        if (codePoint < 0x20) {
                            builder.append(String.format("\\u%04x", codePoint));
                        } else {
                            builder.appendCodePoint(codePoint);
                        }
                    }
                }
            });
            return builder.append('"').toString();
        }

        private String readValue() {
            skipWhitespace();
            char ch = text.charAt(position);
            if (ch == '{' || ch == '[') {
                char close = ch == '{' ? '}' : ']';
                position++;
                List<String> parts = new ArrayList<>();
                skipWhitespace();
                while (text.charAt(position) != close) {
                    String part = readValue();
                    if (ch == '{') {
                        skipWhitespace();
                        position++;
                        part += ":" + readValue();
                    }
                    parts.add(part);
                    skipWhitespace();
                    if (text.charAt(position) == ',') {
                        position++;
                    }
                    skipWhitespace();
                }
                position++;
                return parts.stream().collect(Collectors.joining(",", String.valueOf(ch), String.valueOf(close)));
            }
            if (ch == '"') {
                return quote(readString());
            }
            int start = position;
            while (position < text.length() && ",]}".indexOf(text.charAt(position)) < 0 && !Character.isWhitespace(text.charAt(position))) {
                position++;
            }
            String literal = text.substring(start, position);
            return literal.equals("null") ? "null" : quote(literal);
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

        private void skipWhitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }
    }
}
