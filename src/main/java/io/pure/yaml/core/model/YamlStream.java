package io.pure.yaml.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class YamlStream {
    private final List<YamlDocument> documents = new ArrayList<>();

    public void add(YamlDocument document) {
        documents.add(document);
    }

    public List<YamlDocument> documents() {
        return Collections.unmodifiableList(documents);
    }
}
