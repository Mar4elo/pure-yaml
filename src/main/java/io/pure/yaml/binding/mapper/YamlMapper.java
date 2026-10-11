package io.pure.yaml.binding.mapper;

import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

import io.pure.yaml.annotation.YamlInclude;
import io.pure.yaml.binding.adapter.TypeAdapter;
import io.pure.yaml.binding.introspect.BeanDescriptor;
import io.pure.yaml.binding.introspect.BeanProperty;
import io.pure.yaml.binding.introspect.ClassIntrospector;
import io.pure.yaml.binding.introspect.CreatorDescriptor;
import io.pure.yaml.binding.reflect.Types;
import io.pure.yaml.core.Yaml;
import io.pure.yaml.core.model.YamlAlias;
import io.pure.yaml.core.model.YamlEntry;
import io.pure.yaml.core.model.YamlMapping;
import io.pure.yaml.core.model.YamlNode;
import io.pure.yaml.core.model.YamlScalar;
import io.pure.yaml.core.model.YamlScalarStyle;
import io.pure.yaml.core.model.YamlSequence;
import io.pure.yaml.core.parser.YamlParser;

public final class YamlMapper {
    private static final Pattern INTEGER = Pattern.compile("[-+]?[0-9]+");
    private static final Pattern OCTAL = Pattern.compile("0o[0-7]+");
    private static final Pattern HEXADECIMAL = Pattern.compile("0x[0-9a-fA-F]+");
    private static final Pattern DECIMAL = Pattern.compile("[-+]?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)([eE][-+]?[0-9]+)?");
    private static final Pattern INFINITY = Pattern.compile("[-+]?\\.(inf|Inf|INF)");
    private static final Pattern NOT_A_NUMBER = Pattern.compile("\\.(nan|NaN|NAN)");

    private final YamlMapperConfig config;
    private final ClassIntrospector introspector = new ClassIntrospector();
    private final ConcurrentHashMap<Type, TypeAdapter<?>> adapters = new ConcurrentHashMap<>();

    public YamlMapper() {
        this(YamlMapperConfig.defaults());
    }

    YamlMapper(YamlMapperConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public static YamlMapper createDefault() {
        return new YamlMapper();
    }

    public static YamlMapperBuilder builder() {
        return new YamlMapperBuilder();
    }

    /**
     * Registers an adapter. On write it applies to values of {@code type} and its subtypes
     * (when {@code type} is a class); on read it applies to the exact target type or its raw class.
     */
    public <T> void registerAdapter(Type type, TypeAdapter<T> adapter) {
        adapters.put(Objects.requireNonNull(type, "type"), Objects.requireNonNull(adapter, "adapter"));
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
        return readValue(parse(yaml), type);
    }

    public <T> T readValue(String yaml, YamlTypeRef<T> typeRef) {
        return readValue(parse(yaml), typeRef.getType());
    }

    public <T> T readValue(YamlNode node, Class<T> type) {
        Objects.requireNonNull(type, "type");
        @SuppressWarnings("unchecked")
        Class<T> boxedType = (Class<T>) MethodType.methodType(type).wrap().returnType();
        return boxedType.cast(readValue(node, (Type) type));
    }

    @SuppressWarnings("unchecked")
    public <T> T readValue(YamlNode node, Type type) {
        return (T) readValueInternal(node, type, "$", new ReadContext());
    }

    private YamlNode parse(String yaml) {
        return new YamlParser(yaml, config.processingLimits()).parse().root();
    }

    private YamlNode toYamlNodeInternal(Object value, IdentityHashMap<Object, Boolean> activeObjects) {
        if (value == null) {
            return nullScalar();
        }
        if (value instanceof YamlNode node) {
            return node;
        }
        TypeAdapter<Object> adapter = findWriteAdapter(value.getClass());
        if (adapter != null) {
            enter(value, activeObjects);
            try {
                YamlNode node = adapter.toYaml(value, this);
                return node != null ? node : nullScalar();
            } finally {
                activeObjects.remove(value);
            }
        }
        if (value instanceof String string) {
            // Block scalars are always strings, so multi-line text can be written as a readable literal block.
            return string.indexOf('\n') >= 0 ? new YamlScalar(string, null, null, YamlScalarStyle.LITERAL) : quoted(string);
        }
        if (value instanceof Character character) {
            return quoted(String.valueOf(character));
        }
        if (value instanceof Boolean bool) {
            return YamlScalar.plain(bool ? "true" : "false");
        }
        if (value instanceof Enum<?> enumValue) {
            return quoted(enumValue.name());
        }
        if (value instanceof Number number) {
            return YamlScalar.plain(numberToYaml(number));
        }
        if (value instanceof Optional<?> optional) {
            return optional.map(item -> toYamlNodeInternal(item, activeObjects)).orElseGet(YamlMapper::nullScalar);
        }
        ValueTypes.Codec codec = ValueTypes.forValue(value);
        if (codec != null) {
            return quoted(codec.formatter().apply(value));
        }
        enter(value, activeObjects);
        try {
            if (value instanceof Map<?, ?> map) {
                return mapToYaml(map, activeObjects);
            }
            if (value instanceof Collection<?> collection) {
                return collectionToYaml(collection, activeObjects);
            }
            if (value.getClass().isArray()) {
                return arrayToYaml(value, activeObjects);
            }
            return beanToYaml(value, activeObjects);
        } finally {
            activeObjects.remove(value);
        }
    }

    private YamlNode beanToYaml(Object value, IdentityHashMap<Object, Boolean> activeObjects) {
        BeanDescriptor descriptor = introspector.describe(value.getClass());
        if (descriptor.yamlValueMethod() != null) {
            try {
                return toYamlNodeInternal(descriptor.yamlValueMethod().invoke(value), activeObjects);
            } catch (ReflectiveOperationException exception) {
                throw new YamlMappingException("Cannot invoke @YamlValue for " + value.getClass().getName(), exception);
            }
        }
        YamlMapping mapping = new YamlMapping();
        for (BeanProperty property : descriptor.properties().values()) {
            Object propertyValue = property.accessor().get(value);
            YamlInclude.Include inclusion = property.include() != null ? property.include() : config.defaultInclusion();
            if (propertyValue == null && (!config.writeNulls() || inclusion == YamlInclude.Include.NON_NULL)) {
                continue;
            }
            mapping.put(property.yamlName(), toYamlNodeInternal(propertyValue, activeObjects));
        }
        return mapping;
    }

    private YamlMapping mapToYaml(Map<?, ?> map, IdentityHashMap<Object, Boolean> activeObjects) {
        YamlMapping mapping = new YamlMapping();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = mapKeyToYaml(entry.getKey());
            if (mapping.containsKey(key)) {
                throw new YamlMappingException("Map keys collide after conversion to YAML: " + abbreviate(key));
            }
            mapping.put(key, toYamlNodeInternal(entry.getValue(), activeObjects));
        }
        return mapping;
    }

    private static String mapKeyToYaml(Object key) {
        if (key instanceof String string) {
            return string;
        }
        if (key instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (key instanceof Number number) {
            return numberToYaml(number);
        }
        if (key instanceof Boolean || key instanceof Character) {
            return key.toString();
        }
        ValueTypes.Codec codec = key == null ? null : ValueTypes.forValue(key);
        if (codec != null) {
            return codec.formatter().apply(key);
        }
        throw new YamlMappingException("Unsupported map key: " + (key == null ? "null" : key.getClass().getName()));
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

    private static String numberToYaml(Number number) {
        if (number instanceof Double || number instanceof Float) {
            double value = number.doubleValue();
            if (Double.isNaN(value)) {
                return ".nan";
            }
            if (Double.isInfinite(value)) {
                return value > 0 ? ".inf" : "-.inf";
            }
            return number.toString();
        }
        if (number instanceof BigDecimal decimal) {
            // toPlainString() expands the exponent, which is unbounded work for values such as 1E-100000000.
            if (Math.abs((long) decimal.scale()) > 64) {
                return decimal.toString();
            }
            String plain = decimal.toPlainString();
            return plain.length() <= 64 ? plain : decimal.toString();
        }
        return number.toString();
    }

    private Object readValueInternal(YamlNode node, Type type, String path, ReadContext context) {
        Objects.requireNonNull(type, "type");
        Class<?> rawClass = Types.rawClass(type);
        if (YamlNode.class.isAssignableFrom(rawClass)) {
            if (!rawClass.isInstance(node)) {
                throw new YamlMappingException("Expected " + rawClass.getSimpleName() + " at " + path);
            }
            return node;
        }
        if (node instanceof YamlAlias alias && config.resolveAliases()) {
            return context.expand(alias, path, target -> readValueInternal(target, type, path, context));
        }
        context.visitNode(path);
        boolean collection = node instanceof YamlMapping || node instanceof YamlSequence;
        if (collection) {
            context.enterCollection(path);
        }
        try {
            return readNode(node, type, rawClass, path, context);
        } finally {
            if (collection) {
                context.exitCollection();
            }
        }
    }

    private Object readNode(YamlNode node, Type type, Class<?> rawClass, String path, ReadContext context) {
        TypeAdapter<Object> adapter = findReadAdapter(type);
        if (adapter != null) {
            return adapter.fromYaml(node, type, this, path);
        }
        if (isNullNode(node)) {
            if (rawClass.isPrimitive()) {
                throw new YamlMappingException("Cannot assign null to primitive at " + path);
            }
            if (rawClass == Optional.class) {
                return Optional.empty();
            }
            return null;
        }

        if (node instanceof YamlAlias alias) {
            throw aliasResolutionDisabled(alias, path);
        }
        if (rawClass == Object.class) {
            return readUntyped(node, path, context);
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
            return parseBoolean(expectTypedScalarValue(node, path), path);
        }
        if (rawClass.isEnum()) {
            return readEnum(node, rawClass, path);
        }
        if (Number.class.isAssignableFrom(rawClass) || rawClass.isPrimitive()) {
            return readNumber(node, rawClass, path);
        }
        if (rawClass == Optional.class) {
            Type nestedType = type instanceof ParameterizedType parameterizedType
                ? unwrapWildcard(parameterizedType.getActualTypeArguments()[0])
                : Object.class;
            return Optional.ofNullable(readValueInternal(node, nestedType, path, context));
        }
        ValueTypes.Codec codec = ValueTypes.forType(rawClass);
        if (codec != null) {
            return readValueType(node, codec, path);
        }
        if (Map.class.isAssignableFrom(rawClass)) {
            return readMap(node, type, rawClass, path, context);
        }
        if (Collection.class.isAssignableFrom(rawClass)) {
            return readCollection(node, type, rawClass, path, context);
        }
        if (rawClass.isArray()) {
            Type componentType = type instanceof GenericArrayType genericArrayType
                ? genericArrayType.getGenericComponentType()
                : rawClass.componentType();
            return readArray(node, componentType, path, context);
        }

        return readBean(node, type, rawClass, path, context);
    }

    private Object readBean(YamlNode node, Type type, Class<?> rawClass, String path, ReadContext context) {
        YamlMapping mapping = expectMapping(node, path);
        BeanDescriptor descriptor = introspector.describe(rawClass);
        CreatorDescriptor creator = descriptor.creator();
        if (creator == null) {
            throw new YamlMappingException("Type " + rawClass.getName() + " must declare no-args constructor or @YamlCreator constructor");
        }
        Map<TypeVariable<?>, Type> bindings = Types.typeBindings(type);

        List<CreatorDescriptor.CreatorParameter> parameters = creator.parameters();
        Object[] args = new Object[parameters.size()];
        Set<String> creatorNames = new HashSet<>();
        for (int i = 0; i < parameters.size(); i++) {
            CreatorDescriptor.CreatorParameter parameter = parameters.get(i);
            Type parameterType = Types.resolve(parameter.type(), bindings);
            if (parameter.ignored()) {
                args[i] = defaultValue(Types.rawClass(parameterType));
                continue;
            }
            if (parameter.yamlName() == null) {
                throw new YamlMappingException(
                    "Cannot determine the name of parameter #" + i + " of the @YamlCreator constructor of " + rawClass.getName()
                        + "; compile with -parameters or annotate the parameter with @YamlProperty"
                );
            }
            creatorNames.add(parameter.yamlName());
            YamlNode item = mapping.get(parameter.yamlName());
            args[i] = readValueInternal(item == null ? nullScalar() : item, parameterType, path + "." + parameter.yamlName(), context);
        }

        List<YamlEntry> propertyEntries = new ArrayList<>();
        for (YamlEntry entry : mapping.entries()) {
            String key = scalarKey(entry.key(), path);
            if (creatorNames.contains(key)) {
                continue;
            }
            BeanProperty property = descriptor.properties().get(key);
            if (property == null || property.mutator() == null) {
                handleUnknownProperty(key, path);
                continue;
            }
            propertyEntries.add(entry);
        }

        Object instance = creator.invoker().apply(args);
        for (YamlEntry entry : propertyEntries) {
            BeanProperty property = descriptor.properties().get(scalarKey(entry.key(), path));
            Type propertyType = Types.resolve(property.type(), bindings);
            property.mutator().set(instance, readValueInternal(entry.value(), propertyType, path + "." + property.yamlName(), context));
        }
        return instance;
    }

    private void handleUnknownProperty(String key, String path) {
        if (config.failOnUnknownProperties()) {
            throw new YamlMappingException("Unknown property '" + key + "' at " + path);
        }
    }

    private Object readUntyped(YamlNode node, String path, ReadContext context) {
        if (node instanceof YamlMapping) {
            return readMap(node, Map.class, Map.class, path, context);
        }
        if (node instanceof YamlSequence) {
            return readCollection(node, List.class, List.class, path, context);
        }
        YamlScalar scalar = (YamlScalar) node;
        if (scalar.style() != YamlScalarStyle.PLAIN) {
            return scalar.value();
        }
        return resolvePlainScalar(scalar.value(), path);
    }

    /**
     * Resolves a plain scalar using the YAML 1.2 core schema; null values are handled by the caller.
     */
    private Object resolvePlainScalar(String value, String path) {
        switch (value) {
            case "true", "True", "TRUE" -> {
                return Boolean.TRUE;
            }
            case "false", "False", "FALSE" -> {
                return Boolean.FALSE;
            }
            default -> {
            }
        }
        if (INTEGER.matcher(value).matches() || HEXADECIMAL.matcher(value).matches() || OCTAL.matcher(value).matches()) {
            checkNumberLength(value, path);
            BigInteger integer = parseDecimal(value, path).toBigIntegerExact();
            if (integer.bitLength() < Integer.SIZE) {
                return integer.intValue();
            }
            if (integer.bitLength() < Long.SIZE) {
                return integer.longValue();
            }
            return integer;
        }
        if (INFINITY.matcher(value).matches() || NOT_A_NUMBER.matcher(value).matches() || DECIMAL.matcher(value).matches()) {
            checkNumberLength(value, path);
            return readDouble(value, path);
        }
        return value;
    }

    private Object readArray(YamlNode node, Type componentType, String path, ReadContext context) {
        YamlSequence sequence = expectSequence(node, path);
        Object result = Array.newInstance(Types.rawClass(componentType), sequence.size());
        for (int i = 0; i < sequence.size(); i++) {
            Array.set(result, i, readValueInternal(sequence.get(i), componentType, path + "[" + i + "]", context));
        }
        return result;
    }

    private Object readCollection(YamlNode node, Type type, Class<?> rawClass, String path, ReadContext context) {
        YamlSequence sequence = expectSequence(node, path);
        Type elementType = Object.class;
        if (type instanceof ParameterizedType parameterizedType) {
            elementType = unwrapWildcard(parameterizedType.getActualTypeArguments()[0]);
        }
        Collection<Object> result = instantiateCollection(rawClass);
        for (int i = 0; i < sequence.size(); i++) {
            result.add(readValueInternal(sequence.get(i), elementType, path + "[" + i + "]", context));
        }
        return result;
    }

    private Object readMap(YamlNode node, Type type, Class<?> rawClass, String path, ReadContext context) {
        YamlMapping mapping = expectMapping(node, path);
        Type keyType = String.class;
        Type mapValueType = Object.class;
        if (type instanceof ParameterizedType parameterizedType) {
            keyType = unwrapWildcard(parameterizedType.getActualTypeArguments()[0]);
            mapValueType = unwrapWildcard(parameterizedType.getActualTypeArguments()[1]);
        }
        Class<?> keyClass = Types.rawClass(keyType);
        boolean textKeys = keyClass == String.class || keyClass == Object.class;
        if (!textKeys && !isSupportedMapKey(keyClass)) {
            throw new YamlMappingException("Unsupported map key type " + keyClass.getName() + " at " + path);
        }
        Map<Object, Object> result = instantiateMap(rawClass, keyClass);
        for (YamlEntry entry : mapping.entries()) {
            String keyText = scalarKey(entry.key(), path);
            String entryPath = path + "." + keyText;
            Object key = textKeys ? keyText : readValueInternal(entry.key(), keyType, entryPath, context);
            if (key == null) {
                throw new YamlMappingException("Null map key at " + entryPath);
            }
            if (result.containsKey(key)) {
                throw new YamlMappingException("Duplicate map key after conversion at " + entryPath);
            }
            result.put(key, readValueInternal(entry.value(), mapValueType, entryPath, context));
        }
        return result;
    }

    private static boolean isSupportedMapKey(Class<?> keyClass) {
        return keyClass.isEnum()
            || keyClass == Boolean.class
            || keyClass == Character.class
            || Number.class.isAssignableFrom(keyClass)
            || ValueTypes.forType(keyClass) != null;
    }

    private Object readEnum(YamlNode node, Class<?> rawClass, String path) {
        String name = expectScalarValue(node, path);
        for (Object constant : rawClass.getEnumConstants()) {
            Enum<?> enumValue = (Enum<?>) constant;
            if (enumValue.name().equals(name)) {
                return enumValue;
            }
        }
        throw new YamlMappingException("Unknown enum constant '" + abbreviate(name) + "' for " + rawClass.getName() + " at " + path);
    }

    private Object readValueType(YamlNode node, ValueTypes.Codec codec, String path) {
        String lexical = expectScalarValue(node, path);
        try {
            return codec.parser().apply(lexical);
        } catch (RuntimeException exception) {
            throw new YamlMappingException(
                "Invalid " + codec.type().getSimpleName() + " value at " + path + ": " + abbreviate(lexical),
                exception
            );
        }
    }

    private Object readNumber(YamlNode node, Class<?> rawClass, String path) {
        String lexical = expectTypedScalarValue(node, path);
        checkNumberLength(lexical, path);
        Class<?> type = MethodType.methodType(rawClass).wrap().returnType();
        try {
            if (type == Double.class) {
                return readDouble(lexical, path);
            }
            if (type == Float.class) {
                return readFloat(lexical, path);
            }
            if (type == Number.class && (INFINITY.matcher(lexical).matches() || NOT_A_NUMBER.matcher(lexical).matches())) {
                return readDouble(lexical, path);
            }
            if (type == Byte.class) {
                return parseDecimal(lexical, path).byteValueExact();
            }
            if (type == Short.class) {
                return parseDecimal(lexical, path).shortValueExact();
            }
            if (type == Integer.class) {
                return parseDecimal(lexical, path).intValueExact();
            }
            if (type == Long.class) {
                return parseDecimal(lexical, path).longValueExact();
            }
            if (type == BigInteger.class) {
                return checkMagnitude(parseDecimal(lexical, path), lexical, path).toBigIntegerExact();
            }
            if (type == BigDecimal.class || type == Number.class) {
                return checkMagnitude(parseDecimal(lexical, path), lexical, path);
            }
        } catch (ArithmeticException exception) {
            throw new YamlMappingException("Number out of range at " + path + ": " + abbreviate(lexical), exception);
        }
        throw new YamlMappingException("Unsupported numeric type " + rawClass.getName() + " at " + path);
    }

    private double readDouble(String lexical, String path) {
        if (INFINITY.matcher(lexical).matches()) {
            return lexical.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        if (NOT_A_NUMBER.matcher(lexical).matches()) {
            return Double.NaN;
        }
        double value = DECIMAL.matcher(lexical).matches()
            ? Double.parseDouble(lexical)
            : parseDecimal(lexical, path).doubleValue();
        if (Double.isInfinite(value)) {
            throw new YamlMappingException("Number out of range at " + path + ": " + abbreviate(lexical));
        }
        return value;
    }

    private float readFloat(String lexical, String path) {
        if (INFINITY.matcher(lexical).matches()) {
            return lexical.startsWith("-") ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
        }
        if (NOT_A_NUMBER.matcher(lexical).matches()) {
            return Float.NaN;
        }
        float value = DECIMAL.matcher(lexical).matches()
            ? Float.parseFloat(lexical)
            : parseDecimal(lexical, path).floatValue();
        if (Float.isInfinite(value)) {
            throw new YamlMappingException("Number out of range at " + path + ": " + abbreviate(lexical));
        }
        return value;
    }

    private static BigDecimal parseDecimal(String lexical, String path) {
        try {
            if (HEXADECIMAL.matcher(lexical).matches()) {
                return new BigDecimal(new BigInteger(lexical.substring(2), 16));
            }
            if (OCTAL.matcher(lexical).matches()) {
                return new BigDecimal(new BigInteger(lexical.substring(2), 8));
            }
            if (DECIMAL.matcher(lexical).matches()) {
                return new BigDecimal(lexical);
            }
        } catch (NumberFormatException exception) {
            throw new YamlMappingException("Invalid number at " + path + ": " + abbreviate(lexical), exception);
        }
        throw new YamlMappingException("Invalid number at " + path + ": " + abbreviate(lexical));
    }

    private void checkNumberLength(String lexical, String path) {
        if (lexical.length() > config.processingLimits().maxNumberLength()) {
            throw new YamlMappingException("Number length exceeds configured limit at " + path);
        }
    }

    /**
     * Rejects values such as {@code 1e999999999} whose expansion would be disproportionately expensive.
     */
    private BigDecimal checkMagnitude(BigDecimal decimal, String lexical, String path) {
        long integerDigits = (long) decimal.precision() - decimal.scale();
        if (decimal.signum() != 0 && Math.abs(integerDigits) > config.processingLimits().maxNumberLength()) {
            throw new YamlMappingException("Number magnitude exceeds configured limit at " + path + ": " + abbreviate(lexical));
        }
        return decimal;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<Object, Object> instantiateMap(Class<?> rawClass, Class<?> keyClass) {
        if (rawClass.isAssignableFrom(LinkedHashMap.class)) {
            return new LinkedHashMap<>();
        }
        if (rawClass.isAssignableFrom(TreeMap.class)) {
            return new TreeMap<>();
        }
        if (rawClass == EnumMap.class) {
            if (!keyClass.isEnum()) {
                throw new YamlMappingException("EnumMap requires an enum key type, got " + keyClass.getName());
            }
            return new EnumMap(keyClass.asSubclass(Enum.class));
        }
        return (Map<Object, Object>) instantiate(rawClass, "map");
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> instantiateCollection(Class<?> rawClass) {
        if (rawClass.isAssignableFrom(ArrayList.class)) {
            return new ArrayList<>();
        }
        if (rawClass.isAssignableFrom(LinkedHashSet.class)) {
            return new LinkedHashSet<>();
        }
        if (rawClass.isAssignableFrom(TreeSet.class)) {
            return new TreeSet<>();
        }
        if (rawClass.isAssignableFrom(ArrayDeque.class)) {
            return new ArrayDeque<>();
        }
        return (Collection<Object>) instantiate(rawClass, "collection");
    }

    private static Object instantiate(Class<?> rawClass, String kind) {
        if (rawClass.isInterface() || Modifier.isAbstract(rawClass.getModifiers())) {
            throw new YamlMappingException("Unsupported " + kind + " type " + rawClass.getName());
        }
        try {
            return rawClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new YamlMappingException("Cannot instantiate " + kind + " type " + rawClass.getName(), exception);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type.isPrimitive()) {
            return Array.get(Array.newInstance(type, 1), 0);
        }
        return type == Optional.class ? Optional.empty() : null;
    }

    private TypeAdapter<Object> findReadAdapter(Type type) {
        if (adapters.isEmpty()) {
            return null;
        }
        TypeAdapter<?> adapter = adapters.get(type);
        if (adapter == null && !(type instanceof Class<?>)) {
            adapter = adapters.get(Types.rawClass(type));
        }
        return cast(adapter);
    }

    private TypeAdapter<Object> findWriteAdapter(Class<?> type) {
        if (adapters.isEmpty()) {
            return null;
        }
        ArrayDeque<Class<?>> interfaces = new ArrayDeque<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            TypeAdapter<?> adapter = adapters.get(current);
            if (adapter != null) {
                return cast(adapter);
            }
            interfaces.addAll(Arrays.asList(current.getInterfaces()));
        }
        Set<Class<?>> visited = new HashSet<>();
        while (!interfaces.isEmpty()) {
            Class<?> candidate = interfaces.poll();
            if (!visited.add(candidate)) {
                continue;
            }
            TypeAdapter<?> adapter = adapters.get(candidate);
            if (adapter != null) {
                return cast(adapter);
            }
            interfaces.addAll(Arrays.asList(candidate.getInterfaces()));
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static TypeAdapter<Object> cast(TypeAdapter<?> adapter) {
        return (TypeAdapter<Object>) adapter;
    }

    private static void enter(Object value, IdentityHashMap<Object, Boolean> activeObjects) {
        if (activeObjects.put(value, Boolean.TRUE) != null) {
            throw new YamlMappingException("Cyclic object graph detected while serializing " + value.getClass().getName());
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

    /**
     * Reads a scalar for a boolean or numeric target; quoted scalars are accepted only when coercion is enabled.
     */
    private String expectTypedScalarValue(YamlNode node, String path) {
        String value = expectScalarValue(node, path);
        if (!config.coerceQuotedScalars() && ((YamlScalar) node).style() != YamlScalarStyle.PLAIN) {
            throw new YamlMappingException("Expected an unquoted scalar at " + path + ", got quoted: " + abbreviate(value));
        }
        return value;
    }

    private boolean parseBoolean(String lexical, String path) {
        return switch (lexical) {
            case "true", "True", "TRUE" -> true;
            case "false", "False", "FALSE" -> false;
            default -> throw new YamlMappingException("Expected YAML 1.2 boolean at " + path + ", got: " + abbreviate(lexical));
        };
    }

    /**
     * Only plain scalars can be null; quoted {@code "null"} and {@code "~"} are strings.
     */
    private static boolean isNullNode(YamlNode node) {
        if (!(node instanceof YamlScalar scalar)) {
            return false;
        }
        if (scalar.value() == null) {
            return true;
        }
        return scalar.style() == YamlScalarStyle.PLAIN && switch (scalar.value()) {
            case "null", "Null", "NULL", "~" -> true;
            default -> false;
        };
    }

    private String scalarKey(YamlNode key, String path) {
        if (key instanceof YamlAlias alias) {
            if (!config.resolveAliases()) {
                throw aliasResolutionDisabled(alias, path);
            }
            key = alias.target();
        }
        if (!(key instanceof YamlScalar scalar)) {
            throw new YamlMappingException("Only scalar keys are supported at " + path);
        }
        return scalar.value();
    }

    private static YamlMappingException aliasResolutionDisabled(YamlAlias alias, String path) {
        return new YamlMappingException(
            "Alias resolution is not enabled at " + path + ": *" + alias.alias() + " (see YamlMapperBuilder.resolveAliases)"
        );
    }

    /**
     * State of one readValue call: nesting depth and the number of nodes read through aliases,
     * which bounds exponential expansion such as the "billion laughs" input (RFC 9512, 4.2).
     */
    private final class ReadContext {
        private int depth;
        private int expandedNodes;
        private int expansionDepth;

        Object expand(YamlAlias alias, String path, Function<YamlNode, Object> reader) {
            if (alias.target() == null) {
                throw new YamlMappingException("Unresolved alias at " + path + ": *" + alias.alias());
            }
            expansionDepth++;
            try {
                return reader.apply(alias.target());
            } finally {
                expansionDepth--;
            }
        }

        void visitNode(String path) {
            if (expansionDepth > 0 && ++expandedNodes > config.processingLimits().maxAliasExpansion()) {
                throw new YamlMappingException("Alias expansion exceeds configured limit at " + path);
            }
        }

        void enterCollection(String path) {
            if (++depth > config.processingLimits().maxDepth()) {
                throw new YamlMappingException("Nesting depth exceeds configured limit at " + path);
            }
        }

        void exitCollection() {
            depth--;
        }
    }

    private static Type unwrapWildcard(Type type) {
        if (type instanceof WildcardType wildcardType) {
            return wildcardType.getUpperBounds()[0];
        }
        return type;
    }

    private static YamlScalar nullScalar() {
        return YamlScalar.plain("null");
    }

    private static YamlScalar quoted(String value) {
        return new YamlScalar(value, null, null, YamlScalarStyle.DOUBLE_QUOTED);
    }

    private static String abbreviate(String value) {
        return value.length() <= 40 ? value : value.substring(0, 40) + "...";
    }
}
