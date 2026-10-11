package io.pure.yaml.binding.mapper;

import io.pure.yaml.core.YamlException;

public final class YamlMappingException extends YamlException {
    private static final long serialVersionUID = 1L;

    public YamlMappingException(String message) {
        super(message);
    }

    public YamlMappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
