package io.pure.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.pure.yaml.core.Yaml;
import io.pure.yaml.core.model.YamlMapping;
import io.pure.yaml.core.model.YamlScalar;
import io.pure.yaml.core.model.YamlSequence;
import io.pure.yaml.core.parser.YamlParseException;
import io.pure.yaml.core.parser.YamlParser;
import io.pure.yaml.core.parser.YamlProcessingLimits;
import io.pure.yaml.core.writer.YamlWriteException;

class YamlCoreTest {
    @Test
    void parsesSimpleMappingAndSequence() {
        YamlMapping mapping = (YamlMapping) Yaml.parse("""
            name: "Ann"
            age: 30
            roles:
              - "admin"
              - "editor"
            """);
        assertEquals("Ann", ((YamlScalar) mapping.get("name")).value());
        assertEquals("30", ((YamlScalar) mapping.get("age")).value());
        assertInstanceOf(YamlSequence.class, mapping.get("roles"));
    }

    @Test
    void ignoresBomAndComments() {
        YamlMapping mapping = (YamlMapping) Yaml.parse("""
            \uFEFF# header
            ok: true # inline comment
            """);
        assertTrue(mapping.containsKey("ok"));
    }

    @Test
    void rejectsMultiDocumentInputByDefault() {
        assertThrows(YamlParseException.class, () -> Yaml.parse("""
            ---
            a: 1
            ---
            b: 2
            """));
    }

    @Test
    void stringifiesNestedStructure() {
        YamlMapping mapping = new YamlMapping();
        mapping.put("name", new YamlScalar("Ann"));
        YamlSequence roles = new YamlSequence();
        roles.add(new YamlScalar("admin"));
        roles.add(new YamlScalar("editor"));
        mapping.put("roles", roles);

        assertEquals("""
            name: Ann
            roles:
              - admin
              - editor""", Yaml.stringify(mapping));
    }

    @Test
    void parsesAliasesAsDedicatedNodes() {
        YamlMapping mapping = (YamlMapping) Yaml.parse("""
            value: *shared
            """);
        assertTrue(mapping.get("value") instanceof io.pure.yaml.core.model.YamlAlias);
    }

    @Test
    void rejectsCyclicYamlNodeGraphsOnWrite() {
        YamlSequence sequence = new YamlSequence();
        sequence.add(sequence);

        assertThrows(YamlWriteException.class, () -> Yaml.stringify(sequence));
    }

    @Test
    void supportsYamlDirectiveAndDocumentEndMarker() {
        YamlMapping mapping = (YamlMapping) Yaml.parse("""
            %YAML 1.2
            ---
            name: "Ann"
            ...
            """);
        assertEquals("Ann", ((YamlScalar) mapping.get("name")).value());
    }

    @Test
    void parsesQuotedScalarsAndEscapes() {
        YamlMapping mapping = (YamlMapping) Yaml.parse("""
            single: 'it''s fine'
            double: "line\\nnext\\tindent"
            """);
        assertEquals("it's fine", ((YamlScalar) mapping.get("single")).value());
        assertEquals("line\nnext\tindent", ((YamlScalar) mapping.get("double")).value());
    }

    @Test
    void preservesHashInsideQuotedScalars() {
        YamlMapping mapping = (YamlMapping) Yaml.parse("""
            value: "a # b"
            """);
        assertEquals("a # b", ((YamlScalar) mapping.get("value")).value());
    }

    @Test
    void rejectsTabsInIndentation() {
        assertThrows(YamlParseException.class, () -> Yaml.parse("\tkey: value\n"));
    }

    @Test
    void rejectsUnsupportedFlowCollections() {
        assertThrows(YamlParseException.class, () -> Yaml.parse("""
            values: [1, 2]
            """));
    }

    @Test
    void rejectsUnsupportedEscapes() {
        assertThrows(YamlParseException.class, () -> Yaml.parse("""
            value: "\\u0041"
            """));
    }

    @Test
    void enforcesAliasLimit() {
        YamlParser parser = new YamlParser("""
            first: *a
            second: *b
            """, new YamlProcessingLimits(16, 1, 1, 1_000));

        assertThrows(YamlParseException.class, parser::parse);
    }

    @Test
    void enforcesDepthLimit() {
        YamlParser parser = new YamlParser("""
            root:
              child:
                leaf: value
            """, new YamlProcessingLimits(1, 16, 1, 1_000));

        assertThrows(YamlParseException.class, parser::parse);
    }

    @Test
    void enforcesInputSizeLimit() {
        assertThrows(
            YamlParseException.class,
            () -> new YamlParser("name: value", new YamlProcessingLimits(16, 16, 1, 4))
        );
    }
}
