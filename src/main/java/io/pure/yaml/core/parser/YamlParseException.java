package io.pure.yaml.core.parser;

import io.pure.yaml.core.YamlException;

public final class YamlParseException extends YamlException {
    private static final long serialVersionUID = 1L;

    public YamlParseException(String message) {
        super(message);
    }
}
