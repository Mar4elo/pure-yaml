package io.pure.yaml.core;

import io.pure.yaml.core.model.YamlDocument;
import io.pure.yaml.core.model.YamlNode;
import io.pure.yaml.core.parser.YamlParser;
import io.pure.yaml.core.parser.YamlProcessingLimits;
import io.pure.yaml.core.writer.YamlWriter;

public final class Yaml {
    private Yaml() {
    }

    public static YamlNode parse(String text) {
        return new YamlParser(text, YamlProcessingLimits.defaults()).parse().root();
    }

    public static YamlDocument parseDocument(String text) {
        return new YamlParser(text, YamlProcessingLimits.defaults()).parse();
    }

    public static String stringify(YamlNode node) {
        return new YamlWriter().write(node);
    }
}
