package io.pure.yaml.core.model;

public record YamlAlias(String alias, String tag, String anchor) implements YamlNode {
    public YamlAlias(String alias) {
        this(alias, null, null);
    }
}
