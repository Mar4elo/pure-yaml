package io.pure.yaml.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface YamlInclude {
    Include value() default Include.ALWAYS;

    enum Include {
        ALWAYS,
        NON_NULL
    }
}
