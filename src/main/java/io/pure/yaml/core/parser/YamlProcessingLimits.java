package io.pure.yaml.core.parser;

public record YamlProcessingLimits(
    int maxDepth,
    int maxAliases,
    int maxDocuments,
    int maxCharacters,
    int maxNumberLength
) {
    public static final int DEFAULT_MAX_NUMBER_LENGTH = 1_000;

    public YamlProcessingLimits {
        requireAtLeast(maxDepth, 1, "maxDepth");
        requireAtLeast(maxAliases, 0, "maxAliases");
        requireAtLeast(maxDocuments, 1, "maxDocuments");
        requireAtLeast(maxCharacters, 0, "maxCharacters");
        requireAtLeast(maxNumberLength, 1, "maxNumberLength");
    }

    public YamlProcessingLimits(int maxDepth, int maxAliases, int maxDocuments, int maxCharacters) {
        this(maxDepth, maxAliases, maxDocuments, maxCharacters, DEFAULT_MAX_NUMBER_LENGTH);
    }

    public static YamlProcessingLimits defaults() {
        return new YamlProcessingLimits(128, 128, 1, 1_000_000, DEFAULT_MAX_NUMBER_LENGTH);
    }

    private static void requireAtLeast(int value, int minimum, String name) {
        if (value < minimum) {
            throw new IllegalArgumentException(name + " must be at least " + minimum + ", got " + value);
        }
    }
}
