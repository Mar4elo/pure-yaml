package io.pure.yaml.binding.mapper;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

public abstract class YamlTypeRef<T> {
    private final Type type;

    protected YamlTypeRef() {
        Type superClass = getClass().getGenericSuperclass();
        if (!(superClass instanceof ParameterizedType parameterizedType)) {
            throw new IllegalStateException("YamlTypeRef must be created with generic type information");
        }
        this.type = parameterizedType.getActualTypeArguments()[0];
    }

    public Type getType() {
        return type;
    }
}
