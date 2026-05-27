package io.pure.yaml.binding.introspect;

import java.lang.reflect.Method;
import java.util.Map;

public record BeanDescriptor(
    Class<?> type,
    Map<String, BeanProperty> properties,
    CreatorDescriptor creator,
    Method yamlValueMethod
) {
}
