package io.pure.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.pure.yaml.binding.mapper.YamlMapper;
import io.pure.yaml.binding.mapper.YamlMappingException;
import io.pure.yaml.binding.mapper.YamlTypeRef;
import io.pure.yaml.core.Yaml;
import io.pure.yaml.core.model.YamlMapping;
import io.pure.yaml.core.model.YamlNode;
import io.pure.yaml.core.model.YamlScalar;
import io.pure.yaml.core.model.YamlSequence;
import io.pure.yaml.core.model.YamlStream;
import io.pure.yaml.core.model.YamlTags;
import io.pure.yaml.core.parser.YamlParseException;
import io.pure.yaml.core.parser.YamlProcessingLimits;

class YamlTagTest {
    private final YamlMapper mapper = YamlMapper.createDefault();

    @Test
    void resolvesTagShorthandsAndVerbatimTags() {
        YamlSequence sequence = (YamlSequence) Yaml.parse("""
            %TAG !e! tag:example.com,2000:app/
            ---
            - !local foo
            - !!str bar
            - !e!tag%21 baz
            - !<tag:yaml.org,2002:int> 1
            - ! non-specific
            - &a !!str anchored
            - !!str &b anchored too
            - !!map {a: 1}
            - !!seq
              - x
            - !!str
            """);
        List<String> tags = sequence.values().stream().map(YamlNode::tag).toList();
        assertEquals(List.of("!local", YamlTags.STR, "tag:example.com,2000:app/tag!", YamlTags.INT, "!",
            YamlTags.STR, YamlTags.STR, YamlTags.MAP, YamlTags.SEQ, YamlTags.STR), tags);
        assertEquals("a", sequence.get(5).anchor());
        assertEquals("b", sequence.get(6).anchor());
        assertNull(((YamlScalar) sequence.get(9)).value());
    }

    @Test
    void scopesTagHandlesToTheirDocument() {
        YamlProcessingLimits limits = new YamlProcessingLimits(16, 16, 2, 1_000);
        YamlStream stream = Yaml.parseStream("%TAG !m! !my-\n--- !m!light green\n...\n%TAG !! tag:custom:\n--- !!x y", limits);
        assertEquals("!my-light", stream.documents().get(0).root().tag());
        assertEquals("tag:custom:x", stream.documents().get(1).root().tag());
        assertThrows(YamlParseException.class, () -> Yaml.parseStream("%TAG !m! !my-\n--- a\n--- !m!b c", limits));
    }

    @Test
    void writesTagsBack() {
        String yaml = """
            local: !thing value
            core: !!int 42
            verbatim: !<tag:example.com,2000:x> v
            nonSpecific: ! text
            both: &a !!str x
            collection: !!map
              k: v
            sequence: !!seq
              - 1
            empty: !!str
            weird: !<!a!b> v""";
        assertEquals(yaml, Yaml.stringify(Yaml.parse(yaml)));
    }

    @Test
    void usesCoreTagsAsSchemaHints() {
        Map<String, Object> values = mapper.readValue("""
            text: !!str 123
            bool: !!bool "true"
            int: !!int "0x1F"
            float: !!float 1
            null: !!null ""
            empty: !!str
            nonSpecific: ! 42
            local: !thing 7
            """, new YamlTypeRef<Map<String, Object>>() {
        });
        assertEquals("123", values.get("text"));
        assertEquals(true, values.get("bool"));
        assertEquals(31, values.get("int"));
        assertEquals(1.0, values.get("float"));
        assertTrue(values.containsKey("null"));
        assertNull(values.get("null"));
        assertEquals("", values.get("empty"));
        assertEquals("42", values.get("nonSpecific"));
        // Local tags are metadata: the value is resolved as if untagged, no object is constructed.
        assertEquals(7, values.get("local"));
        assertEquals(new BigInteger("5"), mapper.readValue("!!int 5", BigInteger.class));
    }

    @Test
    void rejectsValuesThatContradictTheirCoreTag() {
        assertThrows(YamlMappingException.class, () -> mapper.readValue("!!int abc", Object.class));
        assertThrows(YamlMappingException.class, () -> mapper.readValue("!!bool yes", Object.class));
        assertThrows(YamlMappingException.class, () -> mapper.readValue("!!null value", Object.class));
        assertThrows(YamlMappingException.class, () -> mapper.readValue("!!map [a]", Object.class));
        assertThrows(YamlMappingException.class, () -> mapper.readValue("!!str {a: 1}", Object.class));
    }

    @Test
    void appliesTagsWhenCoercionIsDisabled() {
        YamlMapper strict = YamlMapper.builder().coerceQuotedScalars(false).build();
        assertEquals(5, strict.readValue("!!int \"5\"", int.class));
        assertThrows(YamlMappingException.class, () -> strict.readValue("!!str 5", int.class));
        assertEquals(5, mapper.readValue("!!str 5", int.class));
    }

    @Test
    void rejectsMalformedTags() {
        assertThrows(YamlParseException.class, () -> Yaml.parse("!e!x value"));
        assertThrows(YamlParseException.class, () -> Yaml.parse("!!str !!int 1"));
        assertThrows(YamlParseException.class, () -> Yaml.parse("!<tag:x value"));
        assertThrows(YamlParseException.class, () -> Yaml.parse("!!str%zz value"));
        assertThrows(YamlParseException.class, () -> Yaml.parse("a: &x !!str\nb: !!str *x"));
        assertThrows(YamlParseException.class, () -> Yaml.parse("!!str[a]"));
    }

    @Test
    void readsTagsOnKeysAndInFlowCollections() {
        YamlMapping mapping = (YamlMapping) Yaml.parse("!!str key: [!!int 1, !local {a: !!str b}, !!null]");
        assertEquals(YamlTags.STR, mapping.entries().getFirst().key().tag());
        YamlSequence values = (YamlSequence) mapping.get("key");
        assertEquals(YamlTags.INT, values.get(0).tag());
        assertEquals("!local", values.get(1).tag());
        assertEquals(YamlTags.NULL, values.get(2).tag());
    }
}
