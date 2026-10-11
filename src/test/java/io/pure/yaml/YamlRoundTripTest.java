package io.pure.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import io.pure.yaml.core.Yaml;
import io.pure.yaml.core.YamlException;
import io.pure.yaml.core.model.YamlEntry;
import io.pure.yaml.core.model.YamlMapping;
import io.pure.yaml.core.model.YamlNode;
import io.pure.yaml.core.model.YamlScalar;
import io.pure.yaml.core.model.YamlScalarStyle;
import io.pure.yaml.core.model.YamlSequence;

class YamlRoundTripTest {
    private static final List<String> FRAGMENTS = List.of(
        "a", "Z", "1", "-", "- ", "?", ":", ": ", " :", "#", " #", "'", "''", "\"", "\\", "\\n", "*", "&", "!", "|", ">",
        "%", "@", "`", "[", "]", "{", "}", ",", "---", "...", " ", "\t", "\n", "\r", "\r\n", "\u0000", "\u0007", "\u001B",
        "\u0085", " ", " ", "﻿", "\uD800", "😀", "é", "null", "~", "true", "0x1F", ".inf"
    );

    @Test
    void writerOutputIsAlwaysReadBackUnchanged() {
        Random random = new Random(20261011L);
        for (int i = 0; i < 2_000; i++) {
            YamlNode tree = randomNode(random, 0);
            String yaml = Yaml.stringify(tree);
            YamlNode parsed;
            try {
                parsed = Yaml.parse(yaml);
            } catch (YamlException exception) {
                fail("Cannot read writer output (iteration " + i + "):\n" + yaml, exception);
                return;
            }
            assertSameTree(tree, parsed, yaml);
        }
    }

    @Test
    void parserFailsOnlyWithYamlExceptions() {
        Random random = new Random(42L);
        for (int i = 0; i < 20_000; i++) {
            StringBuilder builder = new StringBuilder();
            int length = random.nextInt(12);
            for (int j = 0; j < length; j++) {
                builder.append(FRAGMENTS.get(random.nextInt(FRAGMENTS.size())));
            }
            String yaml = builder.toString();
            try {
                YamlNode node = Yaml.parse(yaml);
                // Whatever was accepted must be writable and stable after one more round trip.
                String written = Yaml.stringify(node);
                assertSameTree(node, Yaml.parse(written), written);
            } catch (YamlException expected) {
                // Rejected input is fine; any other exception type is a bug.
            } catch (RuntimeException | StackOverflowError unexpected) {
                fail("Unexpected " + unexpected.getClass().getSimpleName() + " for input: " + escape(yaml), unexpected);
            }
        }
    }

    private static YamlNode randomNode(Random random, int depth) {
        int kind = depth >= 5 ? 0 : random.nextInt(4);
        return switch (kind) {
            case 1 -> {
                YamlSequence sequence = new YamlSequence();
                int size = random.nextInt(4);
                for (int i = 0; i < size; i++) {
                    sequence.add(randomNode(random, depth + 1));
                }
                yield sequence;
            }
            case 2 -> {
                YamlMapping mapping = new YamlMapping();
                int size = random.nextInt(4);
                for (int i = 0; i < size; i++) {
                    String key = randomString(random);
                    if (!mapping.containsKey(key)) {
                        mapping.put(new YamlScalar(key, null, null, randomStyle(random)), randomNode(random, depth + 1));
                    }
                }
                yield mapping;
            }
            default -> new YamlScalar(randomString(random), null, null, randomStyle(random));
        };
    }

    private static String randomString(Random random) {
        StringBuilder builder = new StringBuilder();
        int length = random.nextInt(5);
        for (int i = 0; i < length; i++) {
            builder.append(FRAGMENTS.get(random.nextInt(FRAGMENTS.size())));
        }
        return builder.toString();
    }

    private static YamlScalarStyle randomStyle(Random random) {
        return YamlScalarStyle.values()[random.nextInt(YamlScalarStyle.values().length)];
    }

    private static void assertSameTree(YamlNode expected, YamlNode actual, String yaml) {
        switch (expected) {
            case YamlScalar scalar -> {
                YamlScalar actualScalar = assertInstanceOf(YamlScalar.class, actual, yaml);
                String expectedValue = scalar.value() == null ? "null" : scalar.value();
                String actualValue = actualScalar.value() == null ? "null" : actualScalar.value();
                assertEquals(expectedValue, actualValue, () -> "Scalar changed in:\n" + yaml);
            }
            case YamlSequence sequence -> {
                YamlSequence actualSequence = assertInstanceOf(YamlSequence.class, actual, yaml);
                assertEquals(sequence.size(), actualSequence.size(), yaml);
                for (int i = 0; i < sequence.size(); i++) {
                    assertSameTree(sequence.get(i), actualSequence.get(i), yaml);
                }
            }
            case YamlMapping mapping -> {
                YamlMapping actualMapping = assertInstanceOf(YamlMapping.class, actual, yaml);
                assertEquals(mapping.size(), actualMapping.size(), yaml);
                for (int i = 0; i < mapping.size(); i++) {
                    YamlEntry expectedEntry = mapping.entries().get(i);
                    YamlEntry actualEntry = actualMapping.entries().get(i);
                    assertSameTree(expectedEntry.key(), actualEntry.key(), yaml);
                    assertSameTree(expectedEntry.value(), actualEntry.value(), yaml);
                }
            }
            default -> assertEquals(expected, actual, yaml);
        }
    }

    private static String escape(String value) {
        StringBuilder builder = new StringBuilder();
        value.codePoints().forEach(codePoint -> {
            if (codePoint < 0x20 || codePoint > 0x7E) {
                builder.append(String.format("\\u%04X", codePoint));
            } else {
                builder.append((char) codePoint);
            }
        });
        return builder.toString();
    }
}
