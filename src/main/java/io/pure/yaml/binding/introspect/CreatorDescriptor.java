package io.pure.yaml.binding.introspect;

import java.lang.reflect.Type;
import java.util.List;
import java.util.function.Function;

public record CreatorDescriptor(
    List<CreatorParameter> parameters,
    Function<Object[], Object> invoker
) {
    public record CreatorParameter(String yamlName, Type type) {
    }
}
