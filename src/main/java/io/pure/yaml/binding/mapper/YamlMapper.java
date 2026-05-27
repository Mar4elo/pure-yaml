package io.pure.yaml.binding.mapper;

import java.lang.reflect.Array;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.pure.yaml.annotation.YamlInclude;
import io.pure.yaml.binding.adapter.TypeAdapter;
import io.pure.yaml.binding.introspect.BeanDescriptor;
import io.pure.yaml.binding.introspect.BeanProperty;
import io.pure.yaml.binding.introspect.ClassIntrospector;
import io.pure.yaml.binding.introspect.CreatorDescriptor;
import io.pure.yaml.binding.reflect.Types;
import io.pure.yaml.core.Yaml;
import io.pure.yaml.core.model.YamlAlias;
import io.pure.yaml.core.model.YamlMapping;
import io.pure.yaml.core.model.YamlNode;
import io.pure.yaml.core.model.YamlScalar;
import io.pure.yaml.core.model.YamlScalarStyle;
import io.pure.yaml.core.model.YamlSequence;
import io.pure.yaml.core.parser.YamlParser;

public final class YamlMapper {
    private final YamlMapperConfig config;
    private final ClassIntrospector introspector = new ClassIntrospector();
    private final ConcurrentHashMap<Type, TypeAdapter<?>> adapters = new ConcurrentHashMap<>();

    public YamlMapper() {
        this(YamlMapperConfig.defaults());
    }

    YamlMapper(YamlMapperConfig config) {
        this.config = config;
    }

    public static YamlMapper createDefault() {
        return new YamlMapper();
    }

    public static YamlMapperBuilder builder() {
        return new YamlMapperBuilder();
    }

    public <T> void registerAdapter(Type type, TypeAdapter<T> adapter) {
        adapters.put(type, adapter);
    }

    public void unregisterAdapter(Type type) {
        adapters.remove(type);
    }

    public void clearAdapters() {
        adapters.clear();
    }

    public String writeValueAsString(Object value) {
        return Yaml.stringify(toYamlNode(value));
    }

    public YamlNode toYamlNode(Object value) {
        return toYamlNodeInternal(value, new IdentityHashMap<>());
    }

    public <T> T readValue(String yaml, Class<T> type) {
        return readValue(new YamlParser(yaml, config.processingLimits()).parse().root(), type);
    }

    public <T> T readValue(String yaml, YamlTypeRef<T> typeRef) {
        return readValue(new YamlParser(yaml, config.processingLimits()).parse().root(), typeRef.getType());
    }

    public <T> T readValue(YamlNode node, Class<T> type) {
        return type.cast(readValue(node, (Type) type));
    }

    @SuppressWarnings("unchecked")
    public <T> T readValue(YamlNode node, Type type) {
        return (T) readValueInternal(node, type, "$");
    }

    @SuppressWarnings("unchecked")
    private YamlNode toYamlNodeInternal(Object value, IdentityHashMap<Object, Boolean> activeObjects) {
        if (value == null) {
            return YamlScalar.plain("null");
        }

        if (value instanceof YamlNode node) {
            return node;
        }
        if (value instanceof String string) {
            return new YamlScalar(string, null, null, YamlScalarStyle.DOUBLE_QUOTED);
        }
        if (value instanceof Character character) {
            return new YamlScalar(String.valueOf(character), null, null, YamlScalarStyle.DOUBLE_QUOTED);
        }
        if (value instanceof Boolean bool) {
            return YamlScalar.plain(bool ? "true" : "false");
        }
        if (value instanceof Enum<?> enumValue) {
            return new YamlScalar(enumValue.name(), null, null, YamlScalarStyle.DOUBLE_QUOTED);
        }
        if (value instanceof Number number) {
            return YamlScalar.plain(numberToYaml(number));
        }
        if (value instanceof Optional<?> optional) {
            return optional.map(item -> toYamlNodeInternal(item, activeObjects)).orElseGet(() -> YamlScalar.plain("null"));
        }
        if (activeObjects.put(value, Boolean.TRUE) != null) {
            throw new YamlMappingException("Cyclic object graph detected while serializing " + value.getClass().getName());
        }
        try {
            TypeAdapter<Object> adapter = (TypeAdapter<Object>) adapters.get(value.getClass());
            if (adapter != null) {
                return adapter.toYaml(value, this);
            }
            if (value instanceof Map<?, ?> map) {
                return mapToYaml(map, activeObjects);
            }
            if (value instanceof Collection<?> collection) {
                return collectionToYaml(collection, activeObjects);
            }
            if (value.getClass().isArray()) {
                return arrayToYaml(value, activeObjects);
            }

            BeanDescriptor descriptor = introspector.describe(value.getClass());
            if (descriptor.yamlValueMethod() != null) {
                try {
                    return toYamlNodeInternal(descriptor.yamlValueMethod().invoke(value), activeObjects);
                } catch (ReflectiveOperationException exception) {
                    throw new YamlMappingException("Cannot invoke @YamlValue for " + value.getClass().getName(), exception);
                }
            }

            YamlMapping mapping = new YamlMapping();
            YamlInclude.Include defaultInclusion = config.defaultInclusion();
            for (BeanProperty property : descriptor.properties().values()) {
                Object propertyValue = property.accessor().get(value);
                YamlInclude.Include inclusion = property.include() == YamlInclude.Include.ALWAYS ? defaultInclusion : property.include();
                if (!config.writeNulls() && propertyValue == null) {
                    continue;
                }
                if (inclusion == YamlInclude.Include.NON_NULL && propertyValue == null) {
                    continue;
                }
                mapping.put(property.yamlName(), toYamlNodeInternal(propertyValue, activeObjects));
            }
            return mapping;
        } finally {
            activeObjects.remove(value);
        }
    }

    private YamlMapping mapToYaml(Map<?, ?> map, IdentityHashMap<Object, Boolean> activeObjects) {
        YamlMapping mapping = new YamlMapping();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new YamlMappingException("Current Java binding supports only Map<String, ?>");
            }
            mapping.put(key, toYamlNodeInternal(entry.getValue(), activeObjects));
        }
        return mapping;
    }

    private YamlSequence collectionToYaml(Collection<?> collection, IdentityHashMap<Object, Boolean> activeObjects) {
        YamlSequence sequence = new YamlSequence();
        for (Object item : collection) {
            sequence.add(toYamlNodeInternal(item, activeObjects));
        }
        return sequence;
    }

    private YamlSequence arrayToYaml(Object array, IdentityHashMap<Object, Boolean> activeObjects) {
        YamlSequence sequence = new YamlSequence();
        int length = Array.getLength(array);
        for (int i = 0; i < length; i++) {
            sequence.add(toYamlNodeInternal(Array.get(array, i), activeObjects));
        }
        return sequence;
    }

    private String numberToYaml(Number number) {
        if (number instanceof Double value) {
            return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        }
        if (number instanceof Float value) {
            return BigDecimal.valueOf(value.doubleValue()).stripTrailingZeros().toPlainString();
        }
        if (number instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (number instanceof BigInteger integer) {
            return integer.toString();
        }
        return number.toString();
    }

    @SuppressWarnings("unchecked")
    private Object readValueInternal(YamlNode node, Type type, String path) {
        Objects.requireNonNull(type, "type");

        TypeAdapter<Object> adapter = (TypeAdapter<Object>) adapters.get(type);
        if (adapter != null) {
            return adapter.fromYaml(node, type, this, path);
        }

        Class<?> rawClass = Types.rawClass(type);
        if (isNullNode(node)) {
            if (rawClass.isPrimitive()) {
                throw new YamlMappingException("Cannot assign null to primitive at " + path);
            }
            if (Optional.class.isAssignableFrom(rawClass)) {
                return Optional.empty();
            }
            return null;
        }

        if (rawClass == YamlNode.class || YamlNode.class.isAssignableFrom(rawClass)) {
            return node;
        }
        if (rawClass == String.class) {
            return expectScalarValue(node, path);
        }
        if (rawClass == char.class || rawClass == Character.class) {
            String string = expectScalarValue(node, path);
            if (string.length() != 1) {
                throw new YamlMappingException("Expected single character string at " + path);
            }
            return string.charAt(0);
        }
        if (rawClass == boolean.class || rawClass == Boolean.class) {
            return parseBoolean(node, path);
        }
        if (rawClass.isEnum()) {
            return readEnum(node, rawClass, path);
        }
        if (Number.class.isAssignableFrom(rawClass) || rawClass.isPrimitive() && rawClass != boolean.class && rawClass != char.class) {
            return readNumber(node, rawClass, path);
        }
        if (Optional.class.isAssignableFrom(rawClass)) {
            Type nestedType = ((ParameterizedType) type).getActualTypeArguments()[0];
            return Optional.ofNullable(readValueInternal(node, nestedType, path));
        }
        if (Map.class.isAssignableFrom(rawClass)) {
            return readMap(node, type, path);
        }
        if (Collection.class.isAssignableFrom(rawClass)) {
            return readCollection(node, type, rawClass, path);
        }
        if (rawClass.isArray()) {
            return readArray(node, rawClass.componentType(), path);
        }

        return readBean(node, rawClass, path);
    }

    private Object readBean(YamlNode node, Class<?> rawClass, String path) {
        YamlMapping mapping = expectMapping(node, path);
        BeanDescriptor descriptor = introspector.describe(rawClass);
        if (descriptor.creator() == null) {
            throw new YamlMappingException("Type " + rawClass.getName() + " must declare no-args constructor or @YamlCreator constructor");
        }
        if (descriptor.creator().parameters().isEmpty()) {
            Object instance = descriptor.creator().invoker().apply(new Object[0]);
            for (var entry : mapping.entries()) {
                String key = scalarKey(entry.key(), path);
                BeanProperty property = descriptor.properties().get(key);
                if (property == null) {
                    handleUnknownProperty(key, path);
                    continue;
                }
                if (property.mutator() == null) {
                    continue;
                }
                Object propertyValue = readValueInternal(entry.value(), property.type(), path + "." + property.yamlName());
                property.mutator().set(instance, propertyValue);
            }
            return instance;
        }

        Object[] args = new Object[descriptor.creator().parameters().size()];
        HashSet<String> creatorNames = new HashSet<>(descriptor.creator().parameters().size());
        for (int i = 0; i < descriptor.creator().parameters().size(); i++) {
            CreatorDescriptor.CreatorParameter parameter = descriptor.creator().parameters().get(i);
            creatorNames.add(parameter.yamlName());
            YamlNode item = mapping.get(parameter.yamlName());
            args[i] = readValueInternal(item == null ? YamlScalar.plain("null") : item, parameter.type(), path + "." + parameter.yamlName());
        }
        if (config.failOnUnknownProperties()) {
            for (var entry : mapping.entries()) {
                String key = scalarKey(entry.key(), path);
                boolean known = creatorNames.contains(key) || descriptor.properties().containsKey(key);
                if (!known) {
                    handleUnknownProperty(key, path);
                }
            }
        }
        Object instance = descriptor.creator().invoker().apply(args);
        if (!rawClass.isRecord()) {
            for (var entry : mapping.entries()) {
                String key = scalarKey(entry.key(), path);
                BeanProperty property = descriptor.properties().get(key);
                if (property != null && property.mutator() != null) {
                    Object propertyValue = readValueInternal(entry.value(), property.type(), path + "." + property.yamlName());
                    property.mutator().set(instance, propertyValue);
                }
            }
        }
        return instance;
    }

    private void handleUnknownProperty(String key, String path) {
        if (config.failOnUnknownProperties()) {
            throw new YamlMappingException("Unknown property '" + key + "' at " + path);
        }
    }

    private Object readArray(YamlNode node, Class<?> componentType, String path) {
        YamlSequence sequence = expectSequence(node, path);
        Object result = Array.newInstance(componentType, sequence.size());
        for (int i = 0; i < sequence.size(); i++) {
            Array.set(result, i, readValueInternal(sequence.get(i), componentType, path + "[" + i + "]"));
        }
        return result;
    }

    private Object readCollection(YamlNode node, Type type, Class<?> rawClass, String path) {
        YamlSequence sequence = expectSequence(node, path);
        Type elementType = Object.class;
        if (type instanceof ParameterizedType parameterizedType) {
            elementType = unwrapWildcard(parameterizedType.getActualTypeArguments()[0]);
        }
        Collection<Object> result = instantiateCollection(rawClass);
        for (int i = 0; i < sequence.size(); i++) {
            result.add(readValueInternal(sequence.get(i), elementType, path + "[" + i + "]"));
        }
        return result;
    }

    private Object readMap(YamlNode node, Type type, String path) {
        YamlMapping mapping = expectMapping(node, path);
        Type mapValueType = Object.class;
        if (type instanceof ParameterizedType parameterizedType) {
            Type keyType = unwrapWildcard(parameterizedType.getActualTypeArguments()[0]);
            if (Types.rawClass(keyType) != String.class) {
                throw new YamlMappingException("Current Java binding supports only Map<String, ?> at " + path);
            }
            mapValueType = unwrapWildcard(parameterizedType.getActualTypeArguments()[1]);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : mapping.entries()) {
            String key = scalarKey(entry.key(), path);
            result.put(key, readValueInternal(entry.value(), mapValueType, path + "." + key));
        }
        return result;
    }

    private Object readEnum(YamlNode node, Class<?> rawClass, String path) {
        String name = expectScalarValue(node, path);
        for (Object constant : rawClass.getEnumConstants()) {
            Enum<?> enumValue = (Enum<?>) constant;
            if (enumValue.name().equals(name)) {
                return enumValue;
            }
        }
        throw new YamlMappingException("Unknown enum constant '" + name + "' for " + rawClass.getName() + " at " + path);
    }

    private Object readNumber(YamlNode node, Class<?> rawClass, String path) {
        String lexical = expectScalarValue(node, path);
        try {
            if (rawClass == byte.class || rawClass == Byte.class) {
                return new BigDecimal(lexical).byteValueExact();
            }
            if (rawClass == short.class || rawClass == Short.class) {
                return new BigDecimal(lexical).shortValueExact();
            }
            if (rawClass == int.class || rawClass == Integer.class) {
                return new BigDecimal(lexical).intValueExact();
            }
            if (rawClass == long.class || rawClass == Long.class) {
                return new BigDecimal(lexical).longValueExact();
            }
            if (rawClass == float.class || rawClass == Float.class) {
                return new BigDecimal(lexical).floatValue();
            }
            if (rawClass == double.class || rawClass == Double.class) {
                return new BigDecimal(lexical).doubleValue();
            }
            if (rawClass == BigInteger.class) {
                return new BigDecimal(lexical).toBigIntegerExact();
            }
            if (rawClass == BigDecimal.class) {
                return new BigDecimal(lexical);
            }
        } catch (ArithmeticException exception) {
            throw new YamlMappingException("Number out of range at " + path + ": " + lexical, exception);
        }
        if (rawClass == Number.class) {
            return new BigDecimal(lexical);
        }
        throw new YamlMappingException("Unsupported numeric type " + rawClass.getName() + " at " + path);
    }

    private Collection<Object> instantiateCollection(Class<?> rawClass) {
        if (rawClass.isInterface()) {
            if (List.class.isAssignableFrom(rawClass) || Collection.class == rawClass) {
                return new ArrayList<>();
            }
            if (java.util.Set.class.isAssignableFrom(rawClass)) {
                return new LinkedHashSet<>();
            }
            throw new YamlMappingException("Unsupported collection type " + rawClass.getName());
        }
        try {
            @SuppressWarnings("unchecked")
            Collection<Object> collection = (Collection<Object>) rawClass.getDeclaredConstructor().newInstance();
            return collection;
        } catch (ReflectiveOperationException exception) {
            throw new YamlMappingException("Cannot instantiate collection type " + rawClass.getName(), exception);
        }
    }

    private YamlSequence expectSequence(YamlNode node, String path) {
        if (!(node instanceof YamlSequence sequence)) {
            throw new YamlMappingException("Expected sequence at " + path);
        }
        return sequence;
    }

    private YamlMapping expectMapping(YamlNode node, String path) {
        if (!(node instanceof YamlMapping mapping)) {
            throw new YamlMappingException("Expected mapping at " + path);
        }
        return mapping;
    }

    private String expectScalarValue(YamlNode node, String path) {
        if (node instanceof YamlAlias alias) {
            throw new YamlMappingException("Alias resolution is not enabled at " + path + ": *" + alias.alias());
        }
        if (!(node instanceof YamlScalar scalar)) {
            throw new YamlMappingException("Expected scalar at " + path);
        }
        return scalar.value();
    }

    private boolean parseBoolean(YamlNode node, String path) {
        String lexical = expectScalarValue(node, path);
        return switch (lexical) {
            case "true", "True", "TRUE" -> true;
            case "false", "False", "FALSE" -> false;
            default -> throw new YamlMappingException("Expected YAML 1.2 boolean at " + path + ", got: " + lexical);
        };
    }

    private boolean isNullNode(YamlNode node) {
        if (!(node instanceof YamlScalar scalar)) {
            return false;
        }
        return scalar.value() == null
            || scalar.value().equals("null")
            || scalar.value().equals("Null")
            || scalar.value().equals("NULL")
            || scalar.value().equals("~");
    }

    private String scalarKey(YamlNode key, String path) {
        if (!(key instanceof YamlScalar scalar)) {
            throw new YamlMappingException("Only scalar keys are supported at " + path);
        }
        return scalar.value();
    }

    private Type unwrapWildcard(Type type) {
        if (type instanceof WildcardType wildcardType) {
            return wildcardType.getUpperBounds()[0];
        }
        return type;
    }
}
