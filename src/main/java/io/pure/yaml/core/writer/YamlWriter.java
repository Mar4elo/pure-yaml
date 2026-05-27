package io.pure.yaml.core.writer;

import java.util.IdentityHashMap;

import io.pure.yaml.core.model.YamlAlias;
import io.pure.yaml.core.model.YamlEntry;
import io.pure.yaml.core.model.YamlMapping;
import io.pure.yaml.core.model.YamlNode;
import io.pure.yaml.core.model.YamlScalar;
import io.pure.yaml.core.model.YamlScalarStyle;
import io.pure.yaml.core.model.YamlSequence;

public final class YamlWriter {
    private final String indentUnit;

    public YamlWriter() {
        this("  ");
    }

    public YamlWriter(String indentUnit) {
        this.indentUnit = indentUnit;
    }

    public String write(YamlNode node) {
        StringBuilder builder = new StringBuilder();
        appendNode(builder, node, 0, true, new IdentityHashMap<>());
        if (!builder.isEmpty() && builder.charAt(builder.length() - 1) == '\n') {
            builder.setLength(builder.length() - 1);
        }
        return builder.toString();
    }

    private void appendNode(StringBuilder builder, YamlNode node, int depth, boolean root, IdentityHashMap<YamlNode, Boolean> activeNodes) {
        if (node instanceof YamlScalar scalar) {
            appendScalar(builder, scalar);
            if (root) {
                builder.append('\n');
            }
            return;
        }
        if (node instanceof YamlAlias alias) {
            builder.append('*').append(alias.alias());
            if (root) {
                builder.append('\n');
            }
            return;
        }
        if (activeNodes.put(node, Boolean.TRUE) != null) {
            throw new YamlWriteException("Cyclic YAML node graph detected");
        }
        try {
        if (node instanceof YamlSequence sequence) {
            appendSequence(builder, sequence, depth, activeNodes);
            return;
        }
        if (node instanceof YamlMapping mapping) {
            appendMapping(builder, mapping, depth, activeNodes);
            return;
        }
        throw new YamlWriteException("Unsupported YAML node: " + node.getClass().getName());
        } finally {
            activeNodes.remove(node);
        }
    }

    private void appendSequence(StringBuilder builder, YamlSequence sequence, int depth, IdentityHashMap<YamlNode, Boolean> activeNodes) {
        for (YamlNode item : sequence.values()) {
            indent(builder, depth);
            builder.append("- ");
            if (item instanceof YamlScalar scalar) {
                appendScalar(builder, scalar);
                builder.append('\n');
            } else {
                builder.append('\n');
                appendNode(builder, item, depth + 1, false, activeNodes);
            }
        }
    }

    private void appendMapping(StringBuilder builder, YamlMapping mapping, int depth, IdentityHashMap<YamlNode, Boolean> activeNodes) {
        for (YamlEntry entry : mapping.entries()) {
            String key = renderKey(entry.key());
            indent(builder, depth);
            builder.append(key).append(':');
            if (entry.value() instanceof YamlScalar scalar) {
                builder.append(' ');
                appendScalar(builder, scalar);
                builder.append('\n');
            } else {
                builder.append('\n');
                appendNode(builder, entry.value(), depth + 1, false, activeNodes);
            }
        }
    }

    private String renderKey(YamlNode key) {
        if (!(key instanceof YamlScalar scalar)) {
            throw new YamlWriteException("Current writer supports only scalar mapping keys");
        }
        return renderScalarKey(scalar.value());
    }

    private void appendScalar(StringBuilder builder, YamlScalar scalar) {
        if (scalar.value() == null) {
            builder.append("null");
            return;
        }
        if (scalar.style() == YamlScalarStyle.DOUBLE_QUOTED) {
            appendDoubleQuoted(builder, scalar.value());
            return;
        }
        if (scalar.style() == YamlScalarStyle.SINGLE_QUOTED) {
            builder.append('\'');
            for (int i = 0; i < scalar.value().length(); i++) {
                char ch = scalar.value().charAt(i);
                if (ch == '\'') {
                    builder.append("''");
                } else {
                    builder.append(ch);
                }
            }
            builder.append('\'');
            return;
        }
        if (requiresQuoting(scalar.value())) {
            appendDoubleQuoted(builder, scalar.value());
            return;
        }
        builder.append(scalar.value());
    }

    private String renderScalarKey(String value) {
        if (value == null || value.isBlank() || value.contains(":") || value.contains("#")) {
            StringBuilder builder = new StringBuilder();
            appendDoubleQuoted(builder, value == null ? "null" : value);
            return builder.toString();
        }
        return value;
    }

    private boolean requiresQuoting(String value) {
        if (value.isEmpty()) {
            return true;
        }
        if (value.startsWith(" ") || value.endsWith(" ")) {
            return true;
        }
        if (value.contains("\n") || value.contains("\r") || value.contains("\t")) {
            return true;
        }
        if (value.startsWith("- ") || value.startsWith("*") || value.startsWith("#")) {
            return true;
        }
        return value.contains(": ") || value.contains(" #");
    }

    private void appendDoubleQuoted(StringBuilder builder, String value) {
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(ch);
            }
        }
        builder.append('"');
    }

    private void indent(StringBuilder builder, int depth) {
        for (int i = 0; i < depth; i++) {
            builder.append(indentUnit);
        }
    }
}
