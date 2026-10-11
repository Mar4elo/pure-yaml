package io.pure.yaml.core;

import io.pure.yaml.core.model.YamlDocument;
import io.pure.yaml.core.model.YamlNode;
import io.pure.yaml.core.model.YamlStream;
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
