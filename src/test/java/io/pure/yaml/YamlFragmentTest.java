package io.pure.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.pure.yaml.core.Yaml;
import io.pure.yaml.core.YamlFragment;
import io.pure.yaml.core.model.YamlMapping;
import io.pure.yaml.core.model.YamlNode;
import io.pure.yaml.core.model.YamlScalar;
import io.pure.yaml.core.model.YamlStream;
import io.pure.yaml.core.parser.YamlProcessingLimits;

class YamlFragmentTest {
    private static final YamlProcessingLimits LIMITS = new YamlProcessingLimits(16, 16, 4, 10_000);

    private final YamlNode root = Yaml.parse("""
        foo:
          bar:
            - &first zero
            - one
        "a/b": slash
        "m~n": tilde
        "c%d": percent
        "ключ": unicode
        "": empty key
        shared: &shared {deep: value}
        ref: *shared
        """);

    @Test
    void evaluatesJsonPointers() {
        assertSame(root, YamlFragment.resolve(root, "").orElseThrow());
        assertEquals("one", text(YamlFragment.resolve(root, "/foo/bar/1")));
        assertEquals("slash", text(YamlFragment.resolve(root, "#/a~1b")));
        assertEquals("tilde", text(YamlFragment.resolve(root, "/m~0n")));
        assertEquals("percent", text(YamlFragment.resolve(root, "#/c%25d")));
        assertEquals("unicode", text(YamlFragment.resolve(root, "#/%D0%BA%D0%BB%D1%8E%D1%87")));
        assertEquals("empty key", text(YamlFragment.resolve(root, "/")));
        // Pointers walk through aliases on the representation graph.
        assertEquals("value", text(YamlFragment.resolve(root, "/ref/deep")));
    }

    @Test
    void returnsEmptyWhenNothingMatches() {
        assertTrue(YamlFragment.resolve(root, "/missing").isEmpty());
        assertTrue(YamlFragment.resolve(root, "/foo/bar/2").isEmpty());
        assertTrue(YamlFragment.resolve(root, "/foo/bar/01").isEmpty());
        assertTrue(YamlFragment.resolve(root, "/foo/bar/-").isEmpty());
        assertTrue(YamlFragment.resolve(root, "/foo/bar/0/x").isEmpty());
        assertTrue(YamlFragment.resolve(root, "*missing").isEmpty());
    }

    @Test
    void resolvesAliasFragmentsToTheFirstAnchoredNode() {
        assertEquals("zero", text(YamlFragment.resolve(root, "#*first")));
        assertEquals("value", text(YamlFragment.resolve(YamlFragment.resolve(root, "*shared").orElseThrow(), "/deep")));

        YamlStream stream = Yaml.parseStream("""
            a: &x first
            ---
            b: &x second
            c: &y third
            """, LIMITS);
        assertEquals("first", text(YamlFragment.resolve(stream, "*x")));
        assertEquals("third", text(YamlFragment.resolve(stream, "*y")));
        assertThrows(IllegalArgumentException.class, () -> YamlFragment.resolve(stream, "/a"));
        assertEquals("first", text(YamlFragment.resolve(Yaml.parseStream("a: first", LIMITS), "/a")));
    }

    @Test
    void rejectsMalformedFragments() {
        assertThrows(IllegalArgumentException.class, () -> YamlFragment.resolve(root, "foo"));
        assertThrows(IllegalArgumentException.class, () -> YamlFragment.resolve(root, "/a~2"));
        assertThrows(IllegalArgumentException.class, () -> YamlFragment.resolve(root, "/%zz"));
        assertThrows(IllegalArgumentException.class, () -> YamlFragment.resolve(root, "/%C3"));
        assertTrue(YamlFragment.resolve(new YamlMapping(), "/x").isEmpty());
    }

    private static String text(Optional<YamlNode> node) {
        return ((YamlScalar) node.orElseThrow()).value();
    }
}
