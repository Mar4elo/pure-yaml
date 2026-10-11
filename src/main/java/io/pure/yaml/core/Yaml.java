package io.pure.yaml.core;

import java.io.InputStream;

import io.pure.yaml.core.model.YamlDocument;
import io.pure.yaml.core.model.YamlNode;
import io.pure.yaml.core.model.YamlStream;
import io.pure.yaml.core.parser.YamlEncoding;
import io.pure.yaml.core.parser.YamlParser;
import io.pure.yaml.core.parser.YamlProcessingLimits;
import io.pure.yaml.core.writer.YamlWriter;

public final class Yaml {
    private Yaml() {
    }

    public static YamlNode parse(String text) {
        return parse(text, YamlProcessingLimits.defaults());
    }

    public static YamlNode parse(String text, YamlProcessingLimits limits) {
        return parseDocument(text, limits).root();
    }

    public static YamlDocument parseDocument(String text) {
        return parseDocument(text, YamlProcessingLimits.defaults());
    }

    public static YamlDocument parseDocument(String text, YamlProcessingLimits limits) {
        return new YamlParser(text, limits).parse();
    }

    public static YamlStream parseStream(String text, YamlProcessingLimits limits) {
        return new YamlParser(text, limits).parseStream();
    }

    /**
     * Parses YAML bytes in UTF-8, UTF-16 or UTF-32, detected as described in YAML 1.2.2, 5.2.
     */
    public static YamlNode parse(byte[] bytes) {
        return parse(bytes, YamlProcessingLimits.defaults());
    }

    public static YamlNode parse(byte[] bytes, YamlProcessingLimits limits) {
        return new YamlParser(bytes, limits).parse().root();
    }

    public static YamlStream parseStream(byte[] bytes, YamlProcessingLimits limits) {
        return new YamlParser(bytes, limits).parseStream();
    }

    /**
     * Reads and parses a stream in UTF-8, UTF-16 or UTF-32; no more bytes are read than
     * {@link YamlProcessingLimits#maxCharacters()} allows. The input stream is not closed.
     */
    public static YamlStream parseStream(InputStream input, YamlProcessingLimits limits) {
        return parseStream(YamlEncoding.read(input, limits.maxCharacters()), limits);
    }

    public static String stringify(YamlNode node) {
        return new YamlWriter().write(node);
    }

    public static String stringify(YamlDocument document) {
        return new YamlWriter().write(document);
    }

    public static String stringify(YamlStream stream) {
        return new YamlWriter().write(stream);
    }
}
