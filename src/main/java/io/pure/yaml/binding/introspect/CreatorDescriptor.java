package io.pure.yaml.binding.introspect;

import java.lang.reflect.Type;
import java.util.List;
import java.util.function.Function;

public record CreatorDescriptor(
    List<CreatorParameter> parameters,
    Function<Object[], Object> invoker
) {
    public CreatorDescriptor {
        parameters = List.copyOf(parameters);
    }

    /**
     * @param yamlName YAML property name, or {@code null} when the name is not available at runtime
     * @param ignored  {@code true} when the value must not be read from YAML (for example {@code @YamlIgnore})
     */
    public record CreatorParameter(String yamlName, Type type, boolean ignored) {
    }
}
