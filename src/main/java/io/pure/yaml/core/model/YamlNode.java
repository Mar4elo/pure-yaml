package io.pure.yaml.core.model;

public sealed interface YamlNode permits YamlAlias, YamlMapping, YamlScalar, YamlSequence {
    default String anchor() {
        return null;
    }

    default String tag() {
        return null;
    }
}
