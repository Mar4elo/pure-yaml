package io.pure.yaml.binding.reflect;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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
        if (type instanceof TypeVariable<?> typeVariable) {
            return rawClass(typeVariable.getBounds()[0]);
        }
        throw new IllegalArgumentException("Unsupported type: " + type);
    }

    /**
     * Collects type variable bindings of {@code type} and of its generic superclasses,
     * e.g. {@code Page<User>} binds {@code T -> User}.
     */
    public static Map<TypeVariable<?>, Type> typeBindings(Type type) {
        Map<TypeVariable<?>, Type> bindings = new HashMap<>();
        Type current = type;
        while (current != null) {
            Class<?> rawClass = rawClass(current);
            if (current instanceof ParameterizedType parameterizedType) {
                TypeVariable<?>[] variables = rawClass.getTypeParameters();
                Type[] arguments = parameterizedType.getActualTypeArguments();
                for (int i = 0; i < variables.length && i < arguments.length; i++) {
                    bindings.putIfAbsent(variables[i], resolve(arguments[i], bindings));
                }
            }
            Type superclass = rawClass.getGenericSuperclass();
            current = superclass == null || superclass == Object.class ? null : resolve(superclass, bindings);
        }
        return bindings;
    }

    /**
     * Substitutes bound type variables; unbound variables are erased to their first bound
     * and wildcards are replaced by their upper bound.
     */
    public static Type resolve(Type type, Map<TypeVariable<?>, Type> bindings) {
        if (type instanceof TypeVariable<?> typeVariable) {
            Type bound = bindings.get(typeVariable);
            return bound != null ? bound : rawClass(typeVariable);
        }
        if (type instanceof WildcardType wildcardType) {
            return resolve(wildcardType.getUpperBounds()[0], bindings);
        }
        if (type instanceof GenericArrayType genericArrayType) {
            Type component = resolve(genericArrayType.getGenericComponentType(), bindings);
            if (component instanceof Class<?> componentClass) {
                return componentClass.arrayType();
            }
            return component == genericArrayType.getGenericComponentType() ? genericArrayType : new ResolvedArrayType(component);
        }
        if (type instanceof ParameterizedType parameterizedType) {
            Type[] arguments = parameterizedType.getActualTypeArguments();
            Type[] resolved = new Type[arguments.length];
            boolean changed = false;
            for (int i = 0; i < arguments.length; i++) {
                resolved[i] = resolve(arguments[i], bindings);
                changed |= resolved[i] != arguments[i];
            }
            return changed
                ? new ResolvedParameterizedType(rawClass(parameterizedType), resolved, parameterizedType.getOwnerType())
                : parameterizedType;
        }
        return type;
    }

    private record ResolvedParameterizedType(Class<?> rawType, Type[] arguments, Type ownerType) implements ParameterizedType {
        @Override
        public Type[] getActualTypeArguments() {
            return arguments.clone();
        }

        @Override
        public Type getRawType() {
            return rawType;
        }

        @Override
        public Type getOwnerType() {
            return ownerType;
        }

        // Same contract as the JDK implementation, so instances are interchangeable as map keys.
        @Override
        public boolean equals(Object other) {
            return other instanceof ParameterizedType that
                && rawType.equals(that.getRawType())
                && Objects.equals(ownerType, that.getOwnerType())
                && Arrays.equals(arguments, that.getActualTypeArguments());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(arguments) ^ Objects.hashCode(ownerType) ^ rawType.hashCode();
        }

        @Override
        public String toString() {
            return rawType.getTypeName() + Arrays.stream(arguments).map(Type::getTypeName).collect(Collectors.joining(", ", "<", ">"));
        }
    }

    private record ResolvedArrayType(Type componentType) implements GenericArrayType {
        @Override
        public Type getGenericComponentType() {
            return componentType;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof GenericArrayType that && componentType.equals(that.getGenericComponentType());
        }

        @Override
        public int hashCode() {
            return componentType.hashCode();
        }

        @Override
        public String toString() {
            return componentType.getTypeName() + "[]";
        }
    }
}
