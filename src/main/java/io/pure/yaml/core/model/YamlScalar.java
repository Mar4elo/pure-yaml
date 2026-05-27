package io.pure.yaml.core.model;

public record YamlScalar(
    String value,
    String tag,
    String anchor,
    YamlScalarStyle style
) implements YamlNode {
    public YamlScalar(String value) {
        this(value, null, null, YamlScalarStyle.PLAIN);
    }

    public static YamlScalar plain(String value) {
        return new YamlScalar(value, null, null, YamlScalarStyle.PLAIN);
    }
}
