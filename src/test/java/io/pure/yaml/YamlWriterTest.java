package io.pure.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.pure.yaml.binding.mapper.YamlMapper;
import io.pure.yaml.core.Yaml;
import io.pure.yaml.core.model.YamlDocument;
import io.pure.yaml.core.model.YamlMapping;
import io.pure.yaml.core.model.YamlNode;
import io.pure.yaml.core.model.YamlScalar;
import io.pure.yaml.core.model.YamlScalarStyle;
import io.pure.yaml.core.model.YamlSequence;
import io.pure.yaml.core.model.YamlStream;
import io.pure.yaml.core.parser.YamlParseException;
import io.pure.yaml.core.parser.YamlProcessingLimits;
import io.pure.yaml.core.writer.YamlWriteException;
import io.pure.yaml.core.writer.YamlWriter;

class YamlWriterTest {
    private static final YamlProcessingLimits LIMITS = new YamlProcessingLimits(16, 16, 8, 100_000);

    @Test
    void writesEmptyNodesAsEmptyValues() {
        String yaml = "a:\nb: &x\nlist:\n  -\n  - &y\n  - x";
        assertEquals(yaml, Yaml.stringify(Yaml.parse(yaml)));
        assertEquals("", Yaml.stringify(Yaml.parse("")));
        assertEquals("&root", Yaml.stringify(Yaml.parse("&root")));
        assertNull(((YamlScalar) ((YamlMapping) Yaml.parse(Yaml.stringify(Yaml.parse("a:")))).get("a")).value());
        // Text "null" is still written as text.
        assertEquals("a: null", Yaml.stringify(Yaml.parse("a: null")));
    }

    @Test
    void writesMultiLineTextAsLiteralBlocks() {
        YamlMapping mapping = new YamlMapping();
        mapping.put("clip", plain("line 1\n  indented\n\nline 4\n"));
        mapping.put("strip", plain("no final break"));
        mapping.put("strip2", plain("a\nb"));
        mapping.put("keep", plain("kept\n\n\n"));
        mapping.put("spaces", plain("  leading\nnext\n"));
        mapping.put("list", sequence(plain("in\nsequence\n"), plain("  x\ny")));
        String yaml = Yaml.stringify(mapping);
        assertEquals("""
            clip: |
              line 1
                indented

              line 4
            strip: no final break
            strip2: |-
              a
              b
            keep: |+
              kept


            spaces: |2
                leading
              next
            list:
              - |
                in
                sequence
              - |2-
                  x
                y""", yaml);
        assertSameValues(mapping, Yaml.parse(yaml));
    }

    @Test
    void keepsTrailingEmptyLinesAtTheEndOfTheOutput() {
        YamlMapping mapping = new YamlMapping();
        mapping.put("text", plain("x\n\n"));
        String yaml = Yaml.stringify(mapping);
        assertEquals("text: |+\n  x\n\n", yaml);
        assertEquals("x\n\n", ((YamlScalar) ((YamlMapping) Yaml.parse(yaml)).get("text")).value());
        assertEquals("|+\n  root\n\n", Yaml.stringify(plain("root\n\n")));
        assertEquals("root\n\n", ((YamlScalar) Yaml.parse("|+\n  root\n\n")).value());
    }

    @Test
    void fallsBackToQuotedScalarsWhenALiteralBlockCannotHoldTheValue() {
        assertEquals("a: \"x\\r\\ny\"", Yaml.stringify(map("a", plain("x\r\ny"))));
        assertEquals("a: \"\\n\\n\"", Yaml.stringify(map("a", plain("\n\n"))));
        assertEquals("a: \"x\\ny\"", Yaml.stringify(map("a", new YamlScalar("x\ny", null, null, YamlScalarStyle.DOUBLE_QUOTED))));
        assertEquals("\"multi\\nline\": v", Yaml.stringify(map("multi\nline", plain("v"))));
        // An indentation indicator above 9 is not possible.
        String wide = new YamlWriter("          ").write(map("a", plain("  x\ny")));
        assertEquals("a: \"  x\\ny\"", wide);
    }

    @Test
    void writesBlockScalarsReadFromInputAsLiteralBlocks() {
        assertEquals("a: |\n  folded text\n", Yaml.stringify(Yaml.parse("a: >\n  folded\n  text\n")) + "\n");
        assertEquals("a: |-\n  x\n  y", Yaml.stringify(Yaml.parse("a: |-\n  x\n  y")));
    }

    @Test
    void writesMultiLineStringsOfObjectsAsLiteralBlocks() {
        YamlMapper mapper = YamlMapper.createDefault();
        Note note = new Note("title", "first line\nsecond line\n");
        String yaml = mapper.writeValueAsString(note);
        assertEquals("title: \"title\"\nbody: |\n  first line\n  second line", yaml);
        assertEquals(note, mapper.readValue(yaml, Note.class));
    }

    @Test
    void writesStreamsAndDirectives() {
        YamlStream stream = Yaml.parseStream("""
            a: 1
            ---
            ...
            %YAML 1.2
            ---
            - x
            ---
            text
            """, LIMITS);
        String yaml = Yaml.stringify(stream);
        assertEquals("---\na: 1\n---\n\n...\n%YAML 1.2\n---\n- x\n---\ntext", yaml);
        YamlStream back = Yaml.parseStream(yaml, LIMITS);
        assertEquals(4, back.documents().size());
        assertEquals("%YAML 1.2", back.documents().get(2).versionDirective());
        assertEquals("", Yaml.stringify(new YamlStream()));

        assertEquals("%YAML 1.2\n---\na: 1", Yaml.stringify(new YamlDocument(map("a", plain("1")), "%YAML 1.2")));
        assertThrows(YamlWriteException.class, () -> Yaml.stringify(new YamlDocument(plain("x"), "%YAML 2.0")));
    }

    @Test
    void writesAnchorsPerDocumentInStreams() {
        String yaml = "---\na: &x 1\nb: *x\n---\nc: &x 2\nd: *x";
        assertEquals(yaml, Yaml.stringify(Yaml.parseStream(yaml, LIMITS)));
    }

    @Test
    void limitsImplicitKeysTo1024Characters() {
        String longest = "k".repeat(1024);
        assertEquals("v", ((YamlScalar) ((YamlMapping) Yaml.parse(longest + ": v")).get(longest)).value());
        assertThrows(YamlParseException.class, () -> Yaml.parse(longest + "k: v"));
        assertThrows(YamlParseException.class, () -> Yaml.parse("- " + longest + "k: v"));
        assertThrows(YamlParseException.class, () -> Yaml.parse("[" + longest + "k: v]"));
        // Keys of flow mappings are not implicit keys of block mappings or flow pairs and are not limited.
        assertEquals(1, ((YamlMapping) Yaml.parse("{" + longest + "k: v}")).size());

        assertEquals(longest + ": v", Yaml.stringify(map(longest, plain("v"))));
        assertThrows(YamlWriteException.class, () -> Yaml.stringify(map(longest + "k", plain("v"))));
        assertThrows(YamlWriteException.class, () -> YamlMapper.createDefault().writeValueAsString(Map.of(longest + "k", 1)));
    }

    private static void assertSameValues(YamlNode expected, YamlNode actual) {
        switch (expected) {
            case YamlScalar scalar -> assertEquals(scalar.value(), ((YamlScalar) actual).value());
            case YamlSequence sequence -> {
                List<YamlNode> values = ((YamlSequence) actual).values();
                assertEquals(sequence.size(), values.size());
                for (int i = 0; i < values.size(); i++) {
                    assertSameValues(sequence.get(i), values.get(i));
                }
            }
            case YamlMapping mapping -> mapping.entries().forEach(entry ->
                assertSameValues(entry.value(), ((YamlMapping) actual).get(((YamlScalar) entry.key()).value())));
            default -> throw new AssertionError("Unexpected node " + expected);
        }
    }

    private static YamlScalar plain(String value) {
        return YamlScalar.plain(value);
    }

    private static YamlMapping map(String key, YamlNode value) {
        YamlMapping mapping = new YamlMapping();
        mapping.put(key, value);
        return mapping;
    }

    private static YamlSequence sequence(YamlNode... items) {
        YamlSequence sequence = new YamlSequence();
        for (YamlNode item : items) {
            sequence.add(item);
        }
        return sequence;
    }

    record Note(String title, String body) {
    }
}
