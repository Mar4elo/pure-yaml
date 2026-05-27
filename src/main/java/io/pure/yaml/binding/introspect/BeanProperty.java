package io.pure.yaml.binding.introspect;

import java.lang.reflect.Type;

import io.pure.yaml.annotation.YamlInclude;

public record BeanProperty(
    String yamlName,
    String javaName,
    Type type,
    Accessor accessor,
    Mutator mutator,
    YamlInclude.Include include
) {
    public interface Accessor {
        Object get(Object target);
    }

    public interface Mutator {
        void set(Object target, Object value);
    }
}
