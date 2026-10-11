package io.pure.yaml.core.parser;

/**
 * Limits that protect against pathological input.
 *
 * @param maxDepth            maximum nesting depth of collections, also through aliases
 * @param maxAliases          maximum number of alias nodes in a stream
 * @param maxDocuments        maximum number of documents in a stream
 * @param maxCharacters       maximum input size in characters
 * @param maxNumberLength     maximum length of a numeric literal and magnitude of big numbers
 * @param maxAliasExpansion   number of nodes that aliases may add to a value in any case
 * @param aliasExpansionRatio aliases may also add up to this many nodes per node read from the document;
 *                            {@code 0} makes {@code maxAliasExpansion} an absolute limit
 */
public record YamlProcessingLimits(
    int maxDepth,
    int maxAliases,
    int maxDocuments,
    int maxCharacters,
    int maxNumberLength,
    int maxAliasExpansion,
    int aliasExpansionRatio
) {
    public static final int DEFAULT_MAX_ALIASES = 100_000;
    public static final int DEFAULT_MAX_NUMBER_LENGTH = 1_000;
    public static final int DEFAULT_MAX_ALIAS_EXPANSION = 10_000;
    public static final int DEFAULT_ALIAS_EXPANSION_RATIO = 10;

    public YamlProcessingLimits {
        requireAtLeast(maxDepth, 1, "maxDepth");
        requireAtLeast(maxAliases, 0, "maxAliases");
        requireAtLeast(maxDocuments, 1, "maxDocuments");
        requireAtLeast(maxCharacters, 0, "maxCharacters");
        requireAtLeast(maxNumberLength, 1, "maxNumberLength");
        requireAtLeast(maxAliasExpansion, 0, "maxAliasExpansion");
        requireAtLeast(aliasExpansionRatio, 0, "aliasExpansionRatio");
    }

    public YamlProcessingLimits(int maxDepth, int maxAliases, int maxDocuments, int maxCharacters) {
        this(maxDepth, maxAliases, maxDocuments, maxCharacters, DEFAULT_MAX_NUMBER_LENGTH);
    }

    public YamlProcessingLimits(int maxDepth, int maxAliases, int maxDocuments, int maxCharacters, int maxNumberLength) {
        this(maxDepth, maxAliases, maxDocuments, maxCharacters, maxNumberLength, DEFAULT_MAX_ALIAS_EXPANSION);
    }

    public YamlProcessingLimits(
        int maxDepth,
        int maxAliases,
        int maxDocuments,
        int maxCharacters,
        int maxNumberLength,
        int maxAliasExpansion
    ) {
        this(maxDepth, maxAliases, maxDocuments, maxCharacters, maxNumberLength, maxAliasExpansion, DEFAULT_ALIAS_EXPANSION_RATIO);
    }

    public static YamlProcessingLimits defaults() {
        return new YamlProcessingLimits(
            128,
            DEFAULT_MAX_ALIASES,
            1,
            1_000_000,
            DEFAULT_MAX_NUMBER_LENGTH,
            DEFAULT_MAX_ALIAS_EXPANSION,
            DEFAULT_ALIAS_EXPANSION_RATIO
        );
    }

    private static void requireAtLeast(int value, int minimum, String name) {
        if (value < minimum) {
            throw new IllegalArgumentException(name + " must be at least " + minimum + ", got " + value);
        }
    }
}
