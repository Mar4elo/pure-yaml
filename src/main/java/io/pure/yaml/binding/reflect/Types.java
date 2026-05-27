package io.pure.yaml.binding.reflect;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;

public final class Types {
    private Types() {
    }

    public static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            return rawClass(parameterizedType.getRawType());
        }
        if (type instanceof GenericArrayType genericArrayType) {
            return rawClass(genericArrayType.getGenericComponentType()).arrayType();
        }
        if (type instanceof WildcardType wildcardType) {
            return rawClass(wildcardType.getUpperBounds()[0]);
        }
        throw new IllegalArgumentException("Unsupported type: " + type);
    }
}
