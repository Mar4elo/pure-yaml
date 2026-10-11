package io.pure.yaml.core.writer;

import io.pure.yaml.core.YamlException;

public final class YamlWriteException extends YamlException {
    private static final long serialVersionUID = 1L;

    public YamlWriteException(String message) {
        super(message);
    }
}
