package io.pure.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.pure.yaml.binding.mapper.YamlMapper;
import io.pure.yaml.binding.mapper.YamlTypeRef;
import io.pure.yaml.core.Yaml;
import io.pure.yaml.core.model.YamlMapping;
import io.pure.yaml.core.model.YamlNode;
import io.pure.yaml.core.model.YamlScalar;
import io.pure.yaml.core.model.YamlScalarStyle;
import io.pure.yaml.core.model.YamlSequence;
import io.pure.yaml.core.parser.YamlParseException;
import io.pure.yaml.core.parser.YamlProcessingLimits;

class YamlFlowAndBlockScalarTest {
    @Test
    void readsJsonDocuments() {
        Map<String, Object> json = YamlMapper.createDefault().readValue("""
            {
              "name": "Ann",
              "age": 30,
              "tags": ["admin", "editor"],
              "address": {"city": "Paris", "zip": null},
              "active": true,
              "escaped": "a\\"b\\u00e9\\n"
            }
            """, new YamlTypeRef<Map<String, Object>>() {
        });
        assertEquals("Ann", json.get("name"));
        assertEquals(30, json.get("age"));
        assertEquals(List.of("admin", "editor"), json.get("tags"));
        assertEquals(Map.of("city", "Paris"), Map.of("city", ((Map<?, ?>) json.get("address")).get("city")));
        assertNull(((Map<?, ?>) json.get("address")).get("zip"));
        assertEquals(true, json.get("active"));
        assertEquals("a\"b\u00e9\n", json.get("escaped"));
        assertEquals(List.of(1, 2), YamlMapper.createDefault().readValue("[1,2]", new YamlTypeRef<List<Integer>>() {
        }));
    }

    @Test
    void readsFlowCollectionsInBlockContext() {
        YamlMapping mapping = (YamlMapping) Yaml.parse("""
            list: [a, b , 'c d', "e" ]   # comment
            map: {x: 1, y: [2, 3], z: }
            pairs: [k: v, "json":adjacent, plain value]
            nested: [[1, 2], {a: b}, []]
            implicit: {a, b: c,}
            multi: [first
              line, second,
              { k: v }
              ]
            items:
              - [1, 2]
              - {a: 1}
            """);
        assertEquals(List.of("a", "b", "c d", "e"), texts(mapping.get("list")));
        YamlMapping map = (YamlMapping) mapping.get("map");
        assertEquals(List.of("2", "3"), texts(map.get("y")));
        assertNull(text(map.get("z")));

        YamlSequence pairs = (YamlSequence) mapping.get("pairs");
        assertEquals("v", text(((YamlMapping) pairs.get(0)).get("k")));
        assertEquals("adjacent", text(((YamlMapping) pairs.get(1)).get("json")));
        assertEquals("plain value", text(pairs.get(2)));

        YamlSequence nested = (YamlSequence) mapping.get("nested");
        assertEquals(List.of("1", "2"), texts(nested.get(0)));
        assertEquals("b", text(((YamlMapping) nested.get(1)).get("a")));
        assertEquals(0, ((YamlSequence) nested.get(2)).size());

        YamlMapping implicit = (YamlMapping) mapping.get("implicit");
        assertNull(text(implicit.get("a")));
        assertEquals("c", text(implicit.get("b")));

        YamlSequence multi = (YamlSequence) mapping.get("multi");
        assertEquals("first line", text(multi.get(0)));
        assertEquals("second", text(multi.get(1)));
        assertEquals("v", text(((YamlMapping) multi.get(2)).get("k")));

        YamlSequence items = (YamlSequence) mapping.get("items");
        assertEquals(List.of("1", "2"), texts(items.get(0)));
    }

    @Test
    void readsLongSingleLineJsonInLinearTime() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < 20_000; i++) {
            json.append(i > 0 ? "," : "").append("{\"id\":").append(i).append(",\"tags\":[\"a\",\"b\"]}");
        }
        String text = json.append(']').toString();
        YamlProcessingLimits limits = new YamlProcessingLimits(16, 0, 1, 2_000_000);
        YamlSequence sequence = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> (YamlSequence) Yaml.parse(text, limits));
        assertEquals(20_000, sequence.size());
    }

    @Test
    void writesFlowInputAsBlockYaml() {
        assertEquals("a:\n  - 1\n  - b: c", Yaml.stringify(Yaml.parse("{a: [1, {b: c}]}")));
    }

    @Test
    void readsLiteralAndFoldedBlockScalars() {
        YamlMapping mapping = (YamlMapping) Yaml.parse("""
            literal: |
              line 1
                indented
              # not a comment

            folded: >
              folded
              text

              new paragraph
                more indented
              back
            strip: |-
              text

            keep: |+
              text

            clip: >
              text


            explicit: |2
                leading spaces
            header: |  # comment
              value
            next: end
            """);
        YamlScalar literal = (YamlScalar) mapping.get("literal");
        assertEquals(YamlScalarStyle.LITERAL, literal.style());
        assertEquals("line 1\n  indented\n# not a comment\n", literal.value());
        assertEquals(YamlScalarStyle.FOLDED, ((YamlScalar) mapping.get("folded")).style());
        assertEquals("folded text\nnew paragraph\n  more indented\nback\n", text(mapping.get("folded")));
        assertEquals("text", text(mapping.get("strip")));
        assertEquals("text\n\n", text(mapping.get("keep")));
        assertEquals("text\n", text(mapping.get("clip")));
        assertEquals("  leading spaces\n", text(mapping.get("explicit")));
        assertEquals("value\n", text(mapping.get("header")));
        assertEquals("end", text(mapping.get("next")));
    }

    @Test
    void readsBlockScalarsInSequencesAndDocuments() {
        YamlSequence sequence = (YamlSequence) Yaml.parse("""
            - |
             detected
            - >1
              explicit
            - |
              a
            - b
            """);
        assertEquals(List.of("detected\n", " explicit\n", "a\n", "b"), texts(sequence));
        assertEquals("root\ntext\n", text(Yaml.parse("--- |\nroot\ntext\n")));
        assertEquals("", text(((YamlMapping) Yaml.parse("empty: |\nnext: 1")).get("empty")));
    }

    @Test
    void rejectsMalformedBlockScalars() {
        assertThrows(YamlParseException.class, () -> Yaml.parse("a: |-+\n  x"));
        assertThrows(YamlParseException.class, () -> Yaml.parse("a: |#comment\n  x"));
        assertThrows(YamlParseException.class, () -> Yaml.parse("a: |\n    \n  x"));
        assertThrows(YamlParseException.class, () -> Yaml.parse("[|\n  x\n]"));
    }

    @Test
    void rejectsMalformedFlowCollections() {
        assertThrows(YamlParseException.class, () -> Yaml.parse("[a, b"));
        assertThrows(YamlParseException.class, () -> Yaml.parse("{a: 1 b: 2}"));
        assertThrows(YamlParseException.class, () -> Yaml.parse("[a] b"));
        assertThrows(YamlParseException.class, () -> Yaml.parse("[a]#comment"));
        assertThrows(YamlParseException.class, () -> Yaml.parse("key: {a: 1,\n...\n}"));
        assertThrows(YamlParseException.class, () -> Yaml.parse("{: a, : b}"));
    }

    private static String text(YamlNode node) {
        return ((YamlScalar) node).value();
    }

    private static List<String> texts(YamlNode node) {
        return assertInstanceOf(YamlSequence.class, node).values().stream().map(YamlFlowAndBlockScalarTest::text).toList();
    }
}
