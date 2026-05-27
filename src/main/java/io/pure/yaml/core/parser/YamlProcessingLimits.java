package io.pure.yaml.core.parser;

public record YamlProcessingLimits(
    int maxDepth,
    int maxAliases,
    int maxDocuments,
    int maxCharacters
) {
    public static YamlProcessingLimits defaults() {
        return new YamlProcessingLimits(128, 128, 1, 1_000_000);
    }
}
