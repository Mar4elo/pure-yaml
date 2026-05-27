package io.pure.yaml.core.model;

public record YamlDocument(YamlNode root, String versionDirective) {
    public YamlDocument(YamlNode root) {
        this(root, null);
    }
}
