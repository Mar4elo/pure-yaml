package io.pure.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.pure.yaml.core.Yaml;
import io.pure.yaml.core.YamlException;
import io.pure.yaml.core.model.YamlAlias;
import io.pure.yaml.core.model.YamlMapping;
import io.pure.yaml.core.model.YamlNode;
import io.pure.yaml.core.model.YamlScalar;
import io.pure.yaml.core.model.YamlScalarStyle;
import io.pure.yaml.core.model.YamlSequence;
import io.pure.yaml.core.model.YamlStream;
import io.pure.yaml.core.writer.YamlWriter;
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
            value: "\\q"
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

    @Test
    void readsWriterOutputForNestedCollections() {
        YamlMapping item = new YamlMapping();
        item.put("name", new YamlScalar("Ann"));
        item.put("tags", sequence(new YamlScalar("a"), new YamlScalar("b")));
        YamlMapping root = new YamlMapping();
        root.put("users", sequence(item, sequence(new YamlScalar("x"), sequence(new YamlScalar("y")))));

        String yaml = Yaml.stringify(root);

        assertEquals("""
            users:
              - name: Ann
                tags:
                  - a
                  - b
              - - x
                - - y""", yaml);
        assertEquals(yaml, Yaml.stringify(Yaml.parse(yaml)));
    }

    @Test
    void writesAndReadsEmptyCollections() {
        YamlMapping root = new YamlMapping();
        root.put("list", new YamlSequence());
        root.put("map", new YamlMapping());

        String yaml = Yaml.stringify(root);
        YamlMapping parsed = (YamlMapping) Yaml.parse(yaml);

        assertEquals("list: []\nmap: {}", yaml);
        assertEquals(0, ((YamlSequence) parsed.get("list")).size());
        assertEquals(0, ((YamlMapping) parsed.get("map")).size());
        assertEquals("[]", Yaml.stringify(new YamlSequence()));
    }

    @Test
    void acceptsAnyConsistentIndentation() {
        YamlMapping mapping = (YamlMapping) Yaml.parse("""
            outer:
                inner:
                      leaf: 1
                list:
                   - a
            """);
        YamlMapping outer = (YamlMapping) mapping.get("outer");
        assertEquals("1", scalar(((YamlMapping) outer.get("inner")).get("leaf")));
        assertEquals("a", scalar(((YamlSequence) outer.get("list")).get(0)));
    }

    @Test
    void readsEmptyValuesAsNull() {
        YamlMapping mapping = (YamlMapping) Yaml.parse("""
            first:
            second: 2
            last:
            """);
        assertNull(scalar(mapping.get("first")));
        assertEquals("2", scalar(mapping.get("second")));
        assertNull(scalar(mapping.get("last")));

        YamlSequence sequence = (YamlSequence) Yaml.parse("-\n- b\n-\n  k: v");
        assertNull(scalar(sequence.get(0)));
        assertInstanceOf(YamlMapping.class, sequence.get(2));
    }

    @Test
    void acceptsRepeatedDocumentEndMarkers() {
        YamlMapping mapping = (YamlMapping) Yaml.parse("a: 1\n...\n# trailing\n...\n");
        assertEquals("1", scalar(mapping.get("a")));
        assertEquals(2, Yaml.parseStream("a: 1\n...\n...\n---\nb: 2", new YamlProcessingLimits(8, 8, 2, 100)).documents().size());
    }

    @Test
    void parserInstanceCanBeReused() {
        YamlParser parser = new YamlParser("- a: 1\n  b: *x\n- c", new YamlProcessingLimits(8, 1, 2, 100));
        YamlNode first = parser.parse().root();
        YamlNode second = parser.parse().root();
        assertEquals(Yaml.stringify(first), Yaml.stringify(second));
        assertEquals(1, parser.parseStream().documents().size());
    }

    @Test
    void foldsMultiLinePlainScalars() {
        YamlMapping mapping = (YamlMapping) Yaml.parse("""
            plain: first
              second

              third # comment
            block:
              This unquoted scalar
              spans many lines.
            next: x
            """);
        assertEquals("first second\nthird", scalar(mapping.get("plain")));
        assertEquals("This unquoted scalar spans many lines.", scalar(mapping.get("block")));
        assertEquals("x", scalar(mapping.get("next")));

        YamlSequence sequence = (YamlSequence) Yaml.parse("- single multiline\n - sequence entry\n- b");
        assertEquals("single multiline - sequence entry", scalar(sequence.get(0)));
        assertEquals("b", scalar(sequence.get(1)));
        assertEquals("1st non-empty\n2nd non-empty 3rd non-empty", scalar(Yaml.parse("1st non-empty\n\n 2nd non-empty \n\t3rd non-empty")));
    }

    @Test
    void foldsMultiLineQuotedScalars() {
        assertEquals(" 1st non-empty\n2nd non-empty 3rd non-empty ",
            scalar(Yaml.parse("\" 1st non-empty\n\n 2nd non-empty \n\t3rd non-empty \"")));
        assertEquals(" 1st non-empty\n2nd non-empty 3rd non-empty ",
            scalar(Yaml.parse("' 1st non-empty\n\n 2nd non-empty \n\t3rd non-empty '")));
        assertEquals("folded to a space,\nto a line feed, or \t \tnon-content",
            scalar(Yaml.parse("\"folded \nto a space,\t\n \nto a line feed, or \t\\\n \\ \tnon-content\"")));

        YamlMapping mapping = (YamlMapping) Yaml.parse("""
            quoted: "So does this
              quoted scalar.\\n"
            single: 'it''s
              folded'
            empty: '

              '
            """);
        assertEquals("So does this quoted scalar.\n", scalar(mapping.get("quoted")));
        assertEquals("it's folded", scalar(mapping.get("single")));
        // An escaped space before a line break is content; an escaped line break joins the lines.
        assertEquals("a  b", scalar(Yaml.parse("\"a\\ \n  b\"")));
        assertEquals("ab", scalar(Yaml.parse("\"a\\\n  b\"")));
        assertEquals("\n", scalar(mapping.get("empty")));
        assertEquals("a ...x b", scalar(Yaml.parse("--- \"a\n...x\nb\"")));
    }

    @Test
    void allowsTabsAsSeparatorsButNotAsIndentation() {
        assertEquals("bar", scalar(((YamlMapping) Yaml.parse("foo:\n \tbar")).get("foo")));
        assertEquals("bar baz", scalar(((YamlMapping) Yaml.parse("foo: \"bar\n  \tbaz\"")).get("foo")));
        assertEquals("x x", scalar(((YamlSequence) ((YamlMapping) Yaml.parse("x:\n - x\n  \tx")).get("x")).get(0)));
        assertEquals("-1", scalar(((YamlSequence) Yaml.parse("-\t-1")).get(0)));
        assertEquals("b", scalar(((YamlMapping) Yaml.parse("a:\tb")).get("a")));
        assertThrows(YamlParseException.class, () -> Yaml.parse("foo: \"bar\n\tbaz\""));
    }

    @Test
    void readsContentOnDocumentStartLine() {
        YamlStream stream = Yaml.parseStream("--- foo  # comment\n---\tbar\n--- 'multi\n  line'", new YamlProcessingLimits(8, 8, 3, 100));
        assertEquals(List.of("foo", "bar", "multi line"), stream.documents().stream().map(document -> scalar(document.root())).toList());
    }

    @Test
    void treatsStreamsWithoutContentAsHavingNoDocuments() {
        YamlProcessingLimits limits = new YamlProcessingLimits(8, 8, 2, 100);
        assertEquals(0, Yaml.parseStream("", limits).documents().size());
        assertEquals(0, Yaml.parseStream("# only a comment\n", limits).documents().size());
        assertEquals(0, Yaml.parseStream("...\n# comment\n...\n", limits).documents().size());
        assertEquals(1, Yaml.parseStream("---\n", limits).documents().size());
        assertNull(scalar(Yaml.parse("...\n")));
    }

    @Test
    void allowsByteOrderMarkOnlyAtDocumentStart() {
        YamlProcessingLimits limits = new YamlProcessingLimits(8, 8, 2, 100);
        assertEquals(2, Yaml.parseStream("\uFEFFa: 1\n...\n\uFEFF# prefix\n---\nb: 2", limits).documents().size());
        YamlParseException exception = assertThrows(YamlParseException.class, () -> Yaml.parse("a: 1\n\uFEFF# comment\n"));
        assertTrue(exception.getMessage().contains("Byte order mark"));
    }

    @Test
    void rejectsNonPrintableCharacters() {
        YamlParseException exception = assertThrows(YamlParseException.class, () -> Yaml.parse("a: \"x\u0007\""));
        assertTrue(exception.getMessage().contains("Non-printable character U+0007 at line 1"));
        assertThrows(YamlParseException.class, () -> Yaml.parse("a: \uD800"));
        assertEquals("\u0085 \uD83D\uDE00", scalar(((YamlMapping) Yaml.parse("a: \u0085 \uD83D\uDE00")).get("a")));
    }

    @Test
    void readsEmptyDocumentAsNull() {
        assertNull(scalar(Yaml.parse("")));
        assertNull(scalar(Yaml.parse("# only a comment\n---\n")));
    }

    @Test
    void acceptsCompactSequenceUnderMappingKey() {
        YamlMapping mapping = (YamlMapping) Yaml.parse("""
            list:
            - a
            - b: 1
              c: 2
            next: x
            """);
        YamlSequence list = (YamlSequence) mapping.get("list");
        assertEquals(2, list.size());
        assertEquals("2", scalar(((YamlMapping) list.get(1)).get("c")));
        assertEquals("x", scalar(mapping.get("next")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "a: 1\n  b: 2",
        "- a\nb: 1",
        "a: 1\n- b",
        "a:\n  - x\n  b: 1",
        "a: |\n  text",
        "a: >\n  text",
        "a: &anchor value",
        "a: !!str value",
        "a: b: c",
        "a: \"x\" y",
        "a: - b",
        "? complex\n: value",
        "a: 1\na: 2",
        "%TAG ! tag:example.com,2000:\n---\na: 1",
        "%YAML 2.0\n---\na: 1",
        "%YAML 1.2\na: 1",
        "--- a: b\n",
        "--- - a\n",
        "a: 'multi\nline'",
        "a: \"x\n...\ny\"",
        "a: b\n  c: d",
        "-\t- a",
        "- \t- a",
        "foo:\n  a: 1\n  \tb: 2",
        "a: 1\n\uFEFFb: 2",
        "a: x\u0001y",
        "a: \u007F",
        "*alias: value",
        "a: @reserved",
    })
    void rejectsUnsupportedOrMalformedInputInsteadOfDroppingIt(String yaml) {
        assertThrows(YamlParseException.class, () -> Yaml.parse(yaml));
    }

    @Test
    void handlesQuotesAndCommentsContextually() {
        YamlMapping mapping = (YamlMapping) Yaml.parse("""
            plain: don't stop # comment
            "quoted \\" # key": "value \\" # not a comment" # comment
            'single '' quote': 'it''s # fine'
            url: http://example.com/#anchor
            time: 12:30
            it's: ok
            """);
        assertEquals("don't stop", scalar(mapping.get("plain")));
        assertEquals("value \" # not a comment", scalar(mapping.get("quoted \" # key")));
        assertEquals("it's # fine", scalar(mapping.get("single ' quote")));
        assertEquals("http://example.com/#anchor", scalar(mapping.get("url")));
        assertEquals("12:30", scalar(mapping.get("time")));
        assertEquals("ok", scalar(mapping.get("it's")));
    }

    @Test
    void supportsFullDoubleQuotedEscapeSet() {
        YamlScalar value = (YamlScalar) Yaml.parse("\"\\0\\a\\b\\t\\n\\v\\f\\r\\e\\ \\/\\N\\_\\L\\P\\x41\\u00e9\\U0001F600\"");
        assertEquals("\0\u0007\b\t\n\u000B\f\r\u001B /\u0085\u00A0\u2028\u2029A\u00E9\uD83D\uDE00", value.value());
        assertThrows(YamlParseException.class, () -> Yaml.parse("\"\\x4\""));
        assertThrows(YamlParseException.class, () -> Yaml.parse("\"\\UFFFFFFFF\""));
    }

    @Test
    void parsesStreamsWithinDocumentLimit() {
        String yaml = """
            a: 1
            ---
            b: 2
            ...
            %YAML 1.2
            ---
            c: 3
            """;
        YamlStream stream = Yaml.parseStream(yaml, new YamlProcessingLimits(16, 16, 3, 1_000));
        assertEquals(3, stream.documents().size());
        assertEquals("%YAML 1.2", stream.documents().get(2).versionDirective());
        assertThrows(YamlParseException.class, () -> Yaml.parseStream(yaml, new YamlProcessingLimits(16, 16, 2, 1_000)));
    }

    @Test
    void allowsWhitespaceOnlyLinesWithTabs() {
        YamlMapping mapping = (YamlMapping) Yaml.parse("a: 1\n\t\n  \t# comment\nb: 2");
        assertEquals("2", scalar(mapping.get("b")));
    }

    @Test
    void writesAliasesInline() {
        YamlMapping mapping = new YamlMapping();
        mapping.put("value", new YamlAlias("shared"));
        mapping.put("list", sequence(new YamlAlias("item")));
        assertEquals("value: *shared\nlist:\n  - *item", Yaml.stringify(mapping));
    }

    @Test
    void quotesKeysAndValuesThatWouldChangeMeaning() {
        YamlMapping mapping = new YamlMapping();
        mapping.put("x\n- injected", new YamlScalar("v"));
        mapping.put("*k", new YamlScalar("a:"));
        mapping.put("- dash", new YamlScalar("\"quoted\""));
        mapping.put("---", new YamlScalar("value # comment"));

        String yaml = Yaml.stringify(mapping);
        YamlMapping parsed = (YamlMapping) Yaml.parse(yaml);

        assertEquals("""
            "x\\n- injected": v
            "*k": "a:"
            "- dash": "\\"quoted\\""
            "---": "value # comment\"""", yaml);
        assertEquals("a:", scalar(parsed.get("*k")));
        assertEquals("\"quoted\"", scalar(parsed.get("- dash")));
        assertEquals("value # comment", scalar(parsed.get("---")));
    }

    @Test
    void escapesNonPrintableCharacters() {
        String value = "bell\u0007 nel\u0085 bom\uFEFF ls\u2028 lone\uD800 tab\t";
        String yaml = Yaml.stringify(new YamlScalar(value, null, null, YamlScalarStyle.SINGLE_QUOTED));
        assertEquals("\"bell\\a nel\\N bom\\uFEFF ls\\L lone\\uD800 tab\\t\"", yaml);
        assertEquals(value, scalar(Yaml.parse(yaml)));
    }

    @Test
    void rejectsTagsAndAnchorsOnWrite() {
        assertThrows(YamlWriteException.class, () -> Yaml.stringify(new YamlScalar("v", "!custom", null, YamlScalarStyle.PLAIN)));
        assertThrows(YamlWriteException.class, () -> Yaml.stringify(new YamlMapping(null, "anchor")));
    }

    @Test
    void rejectsDuplicateKeysInModel() {
        YamlMapping mapping = new YamlMapping();
        mapping.put("a", new YamlScalar("1"));
        assertThrows(IllegalArgumentException.class, () -> mapping.put("a", new YamlScalar("2")));
    }

    @Test
    void validatesWriterIndentationAndLimits() {
        assertThrows(IllegalArgumentException.class, () -> new YamlWriter("\t"));
        assertThrows(IllegalArgumentException.class, () -> new YamlWriter(""));
        assertThrows(IllegalArgumentException.class, () -> new YamlProcessingLimits(0, 1, 1, 1));

        YamlMapping mapping = new YamlMapping();
        mapping.put("a", sequence(new YamlScalar("x")));
        String yaml = new YamlWriter("    ").write(mapping);
        assertEquals("a:\n    - x", yaml);
        assertEquals("x", scalar(((YamlSequence) ((YamlMapping) Yaml.parse(yaml)).get("a")).get(0)));
    }

    @Test
    void usesCommonExceptionBase() {
        assertInstanceOf(YamlException.class, assertThrows(YamlParseException.class, () -> Yaml.parse("a: [1]")));
        assertTrue(YamlException.class.isAssignableFrom(YamlWriteException.class));
    }

    private static YamlSequence sequence(YamlNode... items) {
        YamlSequence sequence = new YamlSequence();
        for (YamlNode item : items) {
            sequence.add(item);
        }
        return sequence;
    }

    private static String scalar(YamlNode node) {
        return ((YamlScalar) node).value();
    }
}
