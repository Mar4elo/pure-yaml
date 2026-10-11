package io.pure.yaml.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class YamlMapping implements YamlNode {
    /** Mappings up to this size are searched linearly; larger ones get a hash index. */
    private static final int INDEX_THRESHOLD = 8;

    private final List<YamlEntry> entries = new ArrayList<>();
    private final List<YamlEntry> view = Collections.unmodifiableList(entries);
    private Map<String, YamlNode> scalarIndex;
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
        if (!putIfAbsent(key, value)) {
            throw new IllegalArgumentException("Duplicate mapping key: " + scalarKey(key).value());
        }
    }

    /**
     * Adds an entry unless a scalar key with the same text is already present.
     *
     * @return {@code false} when the key is a duplicate and nothing was added
     */
    public boolean putIfAbsent(YamlNode key, YamlNode value) {
        YamlScalar scalarKey = scalarKey(key);
        if (scalarKey != null && containsKey(scalarKey.value())) {
            return false;
        }
        entries.add(new YamlEntry(key, value));
        if (scalarIndex != null) {
            if (scalarKey != null) {
                scalarIndex.put(scalarKey.value(), value);
            }
        } else if (entries.size() > INDEX_THRESHOLD) {
            scalarIndex = new HashMap<>(entries.size() * 2);
            for (YamlEntry entry : entries) {
                YamlScalar indexed = scalarKey(entry.key());
                if (indexed != null) {
                    scalarIndex.put(indexed.value(), entry.value());
                }
            }
        }
        return true;
    }

    public void put(String key, YamlNode value) {
        put(YamlScalar.plain(key), value);
    }

    public YamlNode get(String key) {
        if (scalarIndex != null) {
            return scalarIndex.get(key);
        }
        for (YamlEntry entry : entries) {
            YamlScalar indexed = scalarKey(entry.key());
            if (indexed != null && Objects.equals(indexed.value(), key)) {
                return entry.value();
            }
        }
        return null;
    }

    public boolean containsKey(String key) {
        if (scalarIndex != null) {
            return scalarIndex.containsKey(key);
        }
        for (YamlEntry entry : entries) {
            YamlScalar indexed = scalarKey(entry.key());
            if (indexed != null && Objects.equals(indexed.value(), key)) {
                return true;
            }
        }
        return false;
    }

    public int size() {
        return entries.size();
    }

    public List<YamlEntry> entries() {
        return view;
    }

    @Override
    public String tag() {
        return tag;
    }

    @Override
    public String anchor() {
        return anchor;
    }

    private static YamlScalar scalarKey(YamlNode key) {
        if (key instanceof YamlAlias alias) {
            return alias.target() instanceof YamlScalar target ? target : null;
        }
        return key instanceof YamlScalar scalar ? scalar : null;
    }
}
