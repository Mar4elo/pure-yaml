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

    /**
     * Adds an entry. Scalar keys, and aliases whose target is a scalar, are indexed by their text
     * and must be unique.
     */
    public void put(YamlNode key, YamlNode value) {
        YamlScalar scalarKey = key instanceof YamlAlias alias && alias.target() instanceof YamlScalar target
            ? target
            : key instanceof YamlScalar scalar ? scalar : null;
        if (scalarKey != null) {
            if (scalarIndex.containsKey(scalarKey.value())) {
                throw new IllegalArgumentException("Duplicate mapping key: " + scalarKey.value());
            }
            scalarIndex.put(scalarKey.value(), value);
        }
        entries.add(new YamlEntry(key, value));
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

    public int size() {
        return entries.size();
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
