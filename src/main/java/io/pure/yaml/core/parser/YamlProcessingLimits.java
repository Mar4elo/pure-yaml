package io.pure.yaml.core.parser;

public record YamlProcessingLimits(
    int maxDepth,
    int maxAliases,
    int maxDocuments,
    int maxCharacters,
    int maxNumberLength,
    int maxAliasExpansion
) {
    public static final int DEFAULT_MAX_NUMBER_LENGTH = 1_000;
    public static final int DEFAULT_MAX_ALIAS_EXPANSION = 10_000;

    public YamlProcessingLimits {
        requireAtLeast(maxDepth, 1, "maxDepth");
        requireAtLeast(maxAliases, 0, "maxAliases");
        requireAtLeast(maxDocuments, 1, "maxDocuments");
        requireAtLeast(maxCharacters, 0, "maxCharacters");
        requireAtLeast(maxNumberLength, 1, "maxNumberLength");
        requireAtLeast(maxAliasExpansion, 0, "maxAliasExpansion");
    }

    public YamlProcessingLimits(int maxDepth, int maxAliases, int maxDocuments, int maxCharacters) {
        this(maxDepth, maxAliases, maxDocuments, maxCharacters, DEFAULT_MAX_NUMBER_LENGTH);
    }

    public YamlProcessingLimits(int maxDepth, int maxAliases, int maxDocuments, int maxCharacters, int maxNumberLength) {
        this(maxDepth, maxAliases, maxDocuments, maxCharacters, maxNumberLength, DEFAULT_MAX_ALIAS_EXPANSION);
    }

    public static YamlProcessingLimits defaults() {
        return new YamlProcessingLimits(128, 128, 1, 1_000_000, DEFAULT_MAX_NUMBER_LENGTH, DEFAULT_MAX_ALIAS_EXPANSION);
    }

    private static void requireAtLeast(int value, int minimum, String name) {
        if (value < minimum) {
            throw new IllegalArgumentException(name + " must be at least " + minimum + ", got " + value);
        }
    }
}
