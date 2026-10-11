package io.pure.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.pure.yaml.binding.mapper.YamlMapper;
import io.pure.yaml.binding.mapper.YamlMappingException;
import io.pure.yaml.binding.mapper.YamlTypeRef;
import io.pure.yaml.core.Yaml;
import io.pure.yaml.core.model.YamlEntry;
import io.pure.yaml.core.model.YamlMapping;
import io.pure.yaml.core.model.YamlNode;
import io.pure.yaml.core.model.YamlScalar;
import io.pure.yaml.core.model.YamlSequence;
import io.pure.yaml.core.parser.YamlParseException;

class YamlKeyTest {
    @Test
    void readsExplicitBlockEntries() {
        YamlMapping mapping = (YamlMapping) Yaml.parse("""
            ? simple
            : value
            ? |
              block key
            : - compact
              - sequence
            ? no value
            ? multi
              line
            :
              nested: map
            plain: implicit
            """);
        assertEquals("value", text(mapping.get("simple")));
        assertEquals(List.of("compact", "sequence"), texts(mapping.get("block key\n")));
        assertTrue(mapping.containsKey("no value"));
        assertNull(text(mapping.get("no value")));
        assertEquals("map", text(((YamlMapping) mapping.get("multi line")).get("nested")));
        assertEquals("implicit", text(mapping.get("plain")));
    }

    @Test
    void readsExplicitEntriesInSequencesAndFlowCollections() {
        YamlSequence sequence = (YamlSequence) Yaml.parse("- ? a\n  : b\n- [? c : d, ? e, f: g]\n- {? h : i, ? j}");
        assertEquals("b", text(((YamlMapping) sequence.get(0)).get("a")));
        YamlSequence pairs = (YamlSequence) sequence.get(1);
        assertEquals("d", text(((YamlMapping) pairs.get(0)).get("c")));
        assertNull(text(((YamlMapping) pairs.get(1)).get("e")));
        assertEquals("g", text(((YamlMapping) pairs.get(2)).get("f")));
        YamlMapping flow = (YamlMapping) sequence.get(2);
        assertEquals("i", text(flow.get("h")));
        assertTrue(flow.containsKey("j"));
    }

    @Test
    void readsEmptyKeys() {
        YamlMapping block = (YamlMapping) Yaml.parse(": value\nother: x");
        assertEquals("value", text(block.get(null)));
        YamlEntry anchored = ((YamlMapping) Yaml.parse("&a : value")).entries().getFirst();
        assertEquals("a", anchored.key().anchor());
        assertNull(((YamlScalar) anchored.key()).value());
        assertEquals("v", text(((YamlMapping) Yaml.parse("{: v}")).get(null)));
        assertEquals("v", text(((YamlMapping) ((YamlSequence) Yaml.parse("[: v]")).get(0)).get(null)));
        assertNull(text(((YamlMapping) Yaml.parse(":")).get(null)));
    }

    @Test
    void writesEmptyKeysBack() {
        for (String yaml : List.of(": value\nother: x", "&a : value", "- : x")) {
            assertEquals(yaml, Yaml.stringify(Yaml.parse(yaml)));
        }
    }

    @Test
    void rejectsKeysThatCannotBeRepresented() {
        assertThrows(YamlParseException.class, () -> Yaml.parse("? [a, b]\n: c"));
        assertThrows(YamlParseException.class, () -> Yaml.parse("? a\n: b\n? a\n: c"));
        assertThrows(YamlParseException.class, () -> Yaml.parse(": a\n: b"));
        assertThrows(YamlParseException.class, () -> Yaml.parse("--- ? a\n: b"));
    }

    @Test
    void rejectsEmptyKeysInJavaBinding() {
        YamlMappingException exception = assertThrows(
            YamlMappingException.class,
            () -> YamlMapper.createDefault().readValue(": v", new YamlTypeRef<Map<String, String>>() {
            })
        );
        assertTrue(exception.getMessage().contains("Empty mapping key cannot be bound"));
        assertEquals(Map.of("a", "b"), YamlMapper.createDefault().readValue("? a\n: b", new YamlTypeRef<Map<String, String>>() {
        }));
    }

    private static String text(YamlNode node) {
        return ((YamlScalar) node).value();
    }

    private static List<String> texts(YamlNode node) {
        return ((YamlSequence) node).values().stream().map(YamlKeyTest::text).toList();
    }
}
