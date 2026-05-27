package io.pure.yaml.binding.mapper;

public final class YamlMappingException extends RuntimeException {
    public YamlMappingException(String message) {
        super(message);
    }

    public YamlMappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
