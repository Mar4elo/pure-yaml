package io.pure.yaml.core.model;

/**
 * Tags of the YAML 1.2 core schema (YAML 1.2.2, 10) and the non-specific tag.
 */
public final class YamlTags {
    /** Prefix of the {@code !!} tag handle. */
    public static final String CORE_PREFIX = "tag:yaml.org,2002:";
    public static final String STR = CORE_PREFIX + "str";
    public static final String INT = CORE_PREFIX + "int";
    public static final String FLOAT = CORE_PREFIX + "float";
    public static final String BOOL = CORE_PREFIX + "bool";
    public static final String NULL = CORE_PREFIX + "null";
    public static final String SEQ = CORE_PREFIX + "seq";
    public static final String MAP = CORE_PREFIX + "map";
    /** The non-specific tag {@code !}: a scalar marked with it is a string. */
    public static final String NON_SPECIFIC = "!";

    private YamlTags() {
    }
}
