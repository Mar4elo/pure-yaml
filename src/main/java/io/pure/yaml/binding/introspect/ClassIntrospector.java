package io.pure.yaml.binding.introspect;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.pure.yaml.annotation.YamlCreator;
import io.pure.yaml.annotation.YamlIgnore;
import io.pure.yaml.annotation.YamlInclude;
import io.pure.yaml.annotation.YamlProperty;
import io.pure.yaml.annotation.YamlValue;
import io.pure.yaml.binding.mapper.YamlMappingException;

public final class ClassIntrospector {
    private final ClassValue<BeanDescriptor> cache = new ClassValue<>() {
        @Override
        protected BeanDescriptor computeValue(Class<?> type) {
            return inspect(type);
        }
    };

    public BeanDescriptor describe(Class<?> type) {
        return cache.get(type);
    }

    private BeanDescriptor inspect(Class<?> type) {
        Method yamlValueMethod = findYamlValueMethod(type);
        Map<String, BeanProperty> properties = type.isRecord() ? inspectRecord(type) : inspectPojo(type);
        CreatorDescriptor creator = type.isRecord() ? recordCreator(type) : pojoCreator(type);
        return new BeanDescriptor(type, properties, creator, yamlValueMethod);
    }

    private Method findYamlValueMethod(Class<?> type) {
        Method result = null;
        for (Method method : type.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(YamlValue.class)) {
                continue;
            }
            if (method.getParameterCount() != 0) {
                throw new YamlMappingException("@YamlValue method must not have parameters: " + type.getName());
            }
            method.setAccessible(true);
            if (result != null) {
                throw new YamlMappingException("Multiple @YamlValue methods found in " + type.getName());
            }
            result = method;
        }
        return result;
    }

    private Map<String, BeanProperty> inspectRecord(Class<?> type) {
        LinkedHashMap<String, BeanProperty> properties = new LinkedHashMap<>();
        for (RecordComponent component : type.getRecordComponents()) {
            if (component.isAnnotationPresent(YamlIgnore.class)) {
                continue;
            }
            Method accessor = component.getAccessor();
            accessor.setAccessible(true);
            String yamlName = propertyName(component.getName(), component.getAnnotation(YamlProperty.class));
            YamlInclude.Include include = includeRule(component.getAnnotation(YamlInclude.class), type.getAnnotation(YamlInclude.class));
            properties.put(yamlName, new BeanProperty(
                yamlName,
                component.getName(),
                component.getGenericType(),
                target -> invoke(accessor, target),
                null,
                include
            ));
        }
        return properties;
    }

    private Map<String, BeanProperty> inspectPojo(Class<?> type) {
        LinkedHashMap<String, BeanProperty> properties = new LinkedHashMap<>();
        for (Field field : collectFields(type)) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            if (field.isAnnotationPresent(YamlIgnore.class)) {
                continue;
            }
            field.setAccessible(true);
            String yamlName = propertyName(field.getName(), field.getAnnotation(YamlProperty.class));
            YamlInclude.Include include = includeRule(field.getAnnotation(YamlInclude.class), type.getAnnotation(YamlInclude.class));
            Method getter = findGetter(type, field);
            Method setter = findSetter(type, field);
            BeanProperty.Accessor accessor = getter != null ? target -> invoke(getter, target) : target -> getField(field, target);
            BeanProperty.Mutator mutator = setter != null ? (target, value) -> invokeSetter(setter, target, value) : (target, value) -> setField(field, target, value);
            properties.putIfAbsent(yamlName, new BeanProperty(
                yamlName,
                field.getName(),
                field.getGenericType(),
                accessor,
                mutator,
                include
            ));
        }
        return properties;
    }

    private CreatorDescriptor recordCreator(Class<?> type) {
        try {
            RecordComponent[] components = type.getRecordComponents();
            Class<?>[] constructorTypes = new Class<?>[components.length];
            List<CreatorDescriptor.CreatorParameter> parameters = new ArrayList<>();
            for (int i = 0; i < components.length; i++) {
                constructorTypes[i] = components[i].getType();
                String yamlName = propertyName(components[i].getName(), components[i].getAnnotation(YamlProperty.class));
                parameters.add(new CreatorDescriptor.CreatorParameter(yamlName, components[i].getGenericType()));
            }
            Constructor<?> constructor = type.getDeclaredConstructor(constructorTypes);
            constructor.setAccessible(true);
            return new CreatorDescriptor(parameters, args -> {
                try {
                    return constructor.newInstance(args);
                } catch (ReflectiveOperationException exception) {
                    throw new YamlMappingException("Cannot instantiate record " + type.getName(), exception);
                }
            });
        } catch (NoSuchMethodException exception) {
            throw new YamlMappingException("Cannot resolve canonical constructor for " + type.getName(), exception);
        }
    }

    private CreatorDescriptor pojoCreator(Class<?> type) {
        Constructor<?> creator = findYamlCreatorConstructor(type);
        if (creator != null) {
            creator.setAccessible(true);
            List<CreatorDescriptor.CreatorParameter> parameters = new ArrayList<>();
            for (Parameter parameter : creator.getParameters()) {
                YamlProperty yamlProperty = parameter.getAnnotation(YamlProperty.class);
                String yamlName = yamlProperty != null ? yamlProperty.value() : parameter.getName();
                parameters.add(new CreatorDescriptor.CreatorParameter(yamlName, parameter.getParameterizedType()));
            }
            return new CreatorDescriptor(parameters, args -> {
                try {
                    return creator.newInstance(args);
                } catch (ReflectiveOperationException exception) {
                    throw new YamlMappingException("Cannot instantiate " + type.getName(), exception);
                }
            });
        }
        try {
            Constructor<?> noArgs = type.getDeclaredConstructor();
            noArgs.setAccessible(true);
            return new CreatorDescriptor(List.of(), args -> {
                try {
                    return noArgs.newInstance();
                } catch (ReflectiveOperationException exception) {
                    throw new YamlMappingException("Cannot instantiate " + type.getName(), exception);
                }
            });
        } catch (NoSuchMethodException exception) {
            return null;
        }
    }

    private Constructor<?> findYamlCreatorConstructor(Class<?> type) {
        Constructor<?> found = null;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (!constructor.isAnnotationPresent(YamlCreator.class)) {
                continue;
            }
            if (found != null) {
                throw new YamlMappingException("Multiple @YamlCreator constructors found in " + type.getName());
            }
            found = constructor;
        }
        return found;
    }

    private List<Field> collectFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                fields.add(field);
            }
            current = current.getSuperclass();
        }
        return fields;
    }

    private Method findGetter(Class<?> type, Field field) {
        String suffix = capitalize(field.getName());
        List<String> candidates = field.getType() == boolean.class || field.getType() == Boolean.class
            ? List.of("is" + suffix, "get" + suffix)
            : List.of("get" + suffix);
        for (String name : candidates) {
            try {
                Method method = type.getMethod(name);
                if (!method.isAnnotationPresent(YamlIgnore.class) && method.getParameterCount() == 0) {
                    method.setAccessible(true);
                    return method;
                }
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private Method findSetter(Class<?> type, Field field) {
        String name = "set" + capitalize(field.getName());
        try {
            Method method = type.getMethod(name, field.getType());
            if (!method.isAnnotationPresent(YamlIgnore.class)) {
                method.setAccessible(true);
                return method;
            }
        } catch (NoSuchMethodException ignored) {
        }
        return null;
    }

    private String propertyName(String fallback, YamlProperty property) {
        return property != null ? property.value() : fallback;
    }

    private YamlInclude.Include includeRule(YamlInclude local, YamlInclude onType) {
        if (local != null) {
            return local.value();
        }
        if (onType != null) {
            return onType.value();
        }
        return YamlInclude.Include.ALWAYS;
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static Object invoke(Method method, Object target) {
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException exception) {
            throw new YamlMappingException("Cannot invoke method " + method, exception);
        }
    }

    private static void invokeSetter(Method method, Object target, Object value) {
        try {
            method.invoke(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new YamlMappingException("Cannot invoke method " + method, exception);
        }
    }

    private static Object getField(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException exception) {
            throw new YamlMappingException("Cannot read field " + field, exception);
        }
    }

    private static void setField(Field field, Object target, Object value) {
        try {
            field.set(target, value);
        } catch (IllegalAccessException exception) {
            throw new YamlMappingException("Cannot write field " + field, exception);
        }
    }
}
