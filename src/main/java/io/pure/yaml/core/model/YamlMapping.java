package io.pure.yaml.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class YamlMapping implements YamlNode {
    private final List<YamlEntry> entries = new ArrayList<>();
    private final Map<String, YamlNode> scalarIndex = new LinkedHashMap<>();
    private final String tag;
    private final String anchor;

    public YamlMapping() {
        this(null, null);
    }

    public YamlMapping(String tag, String anchor) {
        this.tag = tag;
        this.anchor = anchor;
    }

    public void put(YamlNode key, YamlNode value) {
        entries.add(new YamlEntry(key, value));
        if (key instanceof YamlScalar scalar) {
            scalarIndex.putIfAbsent(scalar.value(), value);
        }
    }

    public void put(String key, YamlNode value) {
        put(YamlScalar.plain(key), value);
    }

    public YamlNode get(String key) {
        return scalarIndex.get(key);
    }

    public boolean containsKey(String key) {
        return scalarIndex.containsKey(key);
    }

    public List<YamlEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    @Override
    public String tag() {
        return tag;
    }

    @Override
    public String anchor() {
        return anchor;
    }
}
