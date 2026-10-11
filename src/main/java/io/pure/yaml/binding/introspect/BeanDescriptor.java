package io.pure.yaml.binding.introspect;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record BeanDescriptor(
    Class<?> type,
    Map<String, BeanProperty> properties,
    CreatorDescriptor creator,
    Method yamlValueMethod
) {
    public BeanDescriptor {
        // Descriptors are cached and shared, so they must not be mutable from the outside.
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }
}
