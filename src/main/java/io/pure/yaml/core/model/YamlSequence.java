package io.pure.yaml.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class YamlSequence implements YamlNode {
    private final List<YamlNode> values = new ArrayList<>();
    private final String tag;
    private final String anchor;

    public YamlSequence() {
        this(null, null);
    }

    public YamlSequence(String tag, String anchor) {
        this.tag = tag;
        this.anchor = anchor;
    }

    public void add(YamlNode value) {
        values.add(value);
    }

    public YamlNode get(int index) {
        return values.get(index);
    }

    public int size() {
        return values.size();
    }

    public List<YamlNode> values() {
        return Collections.unmodifiableList(values);
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
