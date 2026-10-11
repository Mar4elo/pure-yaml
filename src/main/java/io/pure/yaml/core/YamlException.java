package io.pure.yaml.core;

public class YamlException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public YamlException(String message) {
        super(message);
    }

    public YamlException(String message, Throwable cause) {
        super(message, cause);
    }
}
