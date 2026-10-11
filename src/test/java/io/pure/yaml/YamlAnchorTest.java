package io.pure.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.pure.yaml.binding.mapper.YamlMapper;
import io.pure.yaml.binding.mapper.YamlMappingException;
import io.pure.yaml.binding.mapper.YamlTypeRef;
import io.pure.yaml.core.Yaml;
import io.pure.yaml.core.model.YamlAlias;
import io.pure.yaml.core.model.YamlMapping;
import io.pure.yaml.core.model.YamlNode;
import io.pure.yaml.core.model.YamlScalar;
import io.pure.yaml.core.model.YamlSequence;
import io.pure.yaml.core.parser.YamlParseException;
import io.pure.yaml.core.parser.YamlProcessingLimits;

class YamlAnchorTest {
    private static final String CONFIG = """
        defaults: &defaults
          timeout: 30
          retries: 3
        services:
          - name: api
            settings: *defaults
          - name: worker
            settings: *defaults
        """;

    @Test
    void resolvesAliasesToAnchoredNodes() {
        YamlMapping root = (YamlMapping) Yaml.parse("""
            scalar: &s value
            list: &l [1, 2]
            map: &m
              key: v
            &k key: anchored key
            refs: [*s, *l, *m, *k]
            *s : alias key
            """);
        assertEquals("s", root.get("scalar").anchor());
        assertEquals("l", root.get("list").anchor());
        assertEquals("m", root.get("map").anchor());
        YamlSequence refs = (YamlSequence) root.get("refs");
        assertSame(root.get("scalar"), ((YamlAlias) refs.get(0)).target());
        assertSame(root.get("list"), ((YamlAlias) refs.get(1)).target());
        assertSame(root.get("map"), ((YamlAlias) refs.get(2)).target());
        assertEquals("key", ((YamlScalar) ((YamlAlias) refs.get(3)).target()).value());
        assertEquals("alias key", ((YamlScalar) root.get("value")).value());
    }

    @Test
    void usesMostRecentAnchorDefinition() {
        YamlSequence sequence = (YamlSequence) Yaml.parse("- &a first\n- *a\n- &a second\n- *a");
        assertEquals("first", target(sequence.get(1)));
        assertEquals("second", target(sequence.get(3)));
    }

    @Test
    void rejectsInvalidAliases() {
        assertThrows(YamlParseException.class, () -> Yaml.parse("a: *missing"));
        assertThrows(YamlParseException.class, () -> Yaml.parse("a: &x\n  - *x"));
        assertThrows(YamlParseException.class, () -> Yaml.parse("a: &x {b: *x}"));
        assertThrows(YamlParseException.class, () -> Yaml.parse("a: &x *y"));
        YamlProcessingLimits limits = new YamlProcessingLimits(16, 16, 2, 1_000);
        assertThrows(YamlParseException.class, () -> Yaml.parseStream("a: &x 1\n---\nb: *x", limits));
    }

    @Test
    void writesAnchorsAndAliases() {
        String yaml = """
            defaults: &defaults
              timeout: 30
            list: &items
              - &first a
              - *first
            copy: *defaults
            nested:
              - &inner
                k: v
              - *inner
            *first : key alias
            empty: &none []""";
        assertEquals(yaml, Yaml.stringify(Yaml.parse(yaml)));
        assertEquals("&root\n- a\n- b", Yaml.stringify(Yaml.parse("&root\n- a\n- b")));
    }

    @Test
    void doesNotResolveAliasesByDefault() {
        YamlMappingException exception = assertThrows(
            YamlMappingException.class,
            () -> YamlMapper.createDefault().readValue(CONFIG, Config.class)
        );
        assertTrue(exception.getMessage().contains("Alias resolution is not enabled at $.services[0].settings"));
    }

    @Test
    void resolvesAliasesWhenEnabled() {
        YamlMapper mapper = YamlMapper.builder().resolveAliases(true).build();
        Config config = mapper.readValue(CONFIG, Config.class);
        assertEquals(new Settings(30, 3), config.services().get(1).settings());

        Map<String, Object> untyped = mapper.readValue("a: &x [1, 2]\nb: *x", new YamlTypeRef<Map<String, Object>>() {
        });
        assertEquals(List.of(1, 2), untyped.get("b"));

        Map<String, String> aliasKeys = mapper.readValue("first: &k key\n*k : second", new YamlTypeRef<Map<String, String>>() {
        });
        assertEquals(Map.of("first", "key", "key", "second"), aliasKeys);
    }

    @Test
    void limitsAliasExpansion() {
        StringBuilder laughs = new StringBuilder("a0: &a0 [lol, lol, lol, lol, lol, lol, lol, lol, lol, lol]\n");
        for (int level = 1; level <= 9; level++) {
            String previous = "*a" + (level - 1);
            laughs.append("a").append(level).append(": &a").append(level).append(" [")
                .append(String.join(", ", Collections.nCopies(10, previous))).append("]\n");
        }
        YamlMapper mapper = YamlMapper.builder()
            .resolveAliases(true)
            .processingLimits(new YamlProcessingLimits(64, 200, 1, 1_000_000))
            .build();
        YamlMappingException exception = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(
            YamlMappingException.class,
            () -> mapper.readValue(laughs.toString(), Object.class)
        ));
        assertTrue(exception.getMessage().contains("Alias expansion exceeds configured limit"));

        // With a ratio of 0, maxAliasExpansion is an absolute limit.
        YamlMapper strict = YamlMapper.builder()
            .resolveAliases(true)
            .processingLimits(new YamlProcessingLimits(64, 16, 1, 1_000, 1_000, 2, 0))
            .build();
        assertThrows(YamlMappingException.class, () -> strict.readValue("a: &x [1, 2, 3]\nb: *x", Object.class));
        // The default ratio lets aliases add up to 10 nodes per node of the document.
        YamlMapper proportional = YamlMapper.builder()
            .resolveAliases(true)
            .processingLimits(new YamlProcessingLimits(64, 16, 1, 1_000, 1_000, 2))
            .build();
        assertEquals(List.of(1, 2, 3), ((Map<?, ?>) proportional.readValue("a: &x [1, 2, 3]\nb: *x", Object.class)).get("b"));
    }

    @Test
    void acceptsManyAliasesWithDefaultLimits() {
        YamlMapper mapper = YamlMapper.builder().resolveAliases(true).build();
        Config config = mapper.readValue(manyServices(10_000), Config.class);
        assertEquals(10_000, config.services().size());
        assertEquals(new Settings(30, 3), config.services().getLast().settings());
    }

    @Test
    void sharesTheValueOfAnAnchoredNodeBetweenItsAliases() {
        YamlMapper mapper = YamlMapper.builder().resolveAliases(true).build();
        Config config = mapper.readValue(CONFIG, Config.class);
        assertSame(config.defaults(), config.services().get(0).settings());
        assertSame(config.services().get(0).settings(), config.services().get(1).settings());

        Map<?, ?> untyped = (Map<?, ?>) mapper.readValue("a: &x {k: v}\nb: *x\nc: *x", Object.class);
        assertSame(untyped.get("a"), untyped.get("b"));
        assertSame(untyped.get("b"), untyped.get("c"));
        // The same anchored node read as two types yields one value per type.
        TwoViews views = mapper.readValue("raw: &x {timeout: 1, retries: 2}\ntyped: *x", TwoViews.class);
        assertEquals(Map.of("timeout", 1, "retries", 2), views.raw());
        assertEquals(new Settings(1, 2), views.typed());
    }

    @Test
    void rejectsLongAliasChainsWithoutExhaustingTheStack() {
        StringBuilder chain = new StringBuilder("l0: &l0 [x]\n");
        for (int level = 1; level <= 20_000; level++) {
            chain.append('l').append(level).append(": &l").append(level).append(" [*l").append(level - 1).append("]\n");
        }
        YamlMapper mapper = YamlMapper.builder()
            .resolveAliases(true)
            .processingLimits(new YamlProcessingLimits(128, 100_000, 1, 1_000_000))
            .build();
        YamlMappingException exception = assertThrows(YamlMappingException.class, () -> mapper.readValue(chain.toString(), Object.class));
        assertTrue(exception.getMessage().contains("Nesting depth exceeds configured limit"));
    }

    @Test
    void limitsNestingDepthThroughAliases() {
        StringBuilder chain = new StringBuilder("l0: &l0 [x]\n");
        for (int level = 1; level <= 20; level++) {
            chain.append("l").append(level).append(": &l").append(level).append(" [*l").append(level - 1).append("]\n");
        }
        YamlMapper mapper = YamlMapper.builder()
            .resolveAliases(true)
            .processingLimits(new YamlProcessingLimits(10, 100, 1, 1_000_000))
            .build();
        YamlMappingException exception = assertThrows(YamlMappingException.class, () -> mapper.readValue(chain.toString(), Object.class));
        assertTrue(exception.getMessage().contains("Nesting depth exceeds configured limit"));
    }

    @Test
    void returnsAliasNodesForTreeTargets() {
        YamlMapping tree = YamlMapper.builder().resolveAliases(true).build().readValue("a: &x 1\nb: *x", YamlMapping.class);
        assertInstanceOf(YamlAlias.class, tree.get("b"));
    }

    private static String manyServices(int aliases) {
        StringBuilder text = new StringBuilder("defaults: &defaults\n  timeout: 30\n  retries: 3\nservices:\n");
        for (int i = 0; i < aliases; i++) {
            text.append("  - name: service").append(i).append("\n    settings: *defaults\n");
        }
        return text.toString();
    }

    private static String target(YamlNode node) {
        return ((YamlScalar) ((YamlAlias) node).target()).value();
    }

    record Settings(int timeout, int retries) {
    }

    record Service(String name, Settings settings) {
    }

    record Config(Settings defaults, List<Service> services) {
    }

    record TwoViews(Map<String, Integer> raw, Settings typed) {
    }
}
