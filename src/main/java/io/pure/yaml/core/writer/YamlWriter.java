package io.pure.yaml.core.writer;

import java.util.IdentityHashMap;
import java.util.Objects;

import io.pure.yaml.core.model.YamlAlias;
import io.pure.yaml.core.model.YamlEntry;
import io.pure.yaml.core.model.YamlMapping;
import io.pure.yaml.core.model.YamlNode;
import io.pure.yaml.core.model.YamlScalar;
import io.pure.yaml.core.model.YamlSequence;

public final class YamlWriter {
    private static final String PLAIN_FORBIDDEN_FIRST = ",[]{}#&*!|>'\"%@`";

    private final String indentUnit;

    public YamlWriter() {
        this("  ");
    }

    public YamlWriter(String indentUnit) {
        Objects.requireNonNull(indentUnit, "indentUnit");
        if (indentUnit.isEmpty() || !indentUnit.chars().allMatch(ch -> ch == ' ')) {
            throw new IllegalArgumentException("Indentation unit must consist of one or more spaces");
        }
        this.indentUnit = indentUnit;
    }

    public String write(YamlNode node) {
        Objects.requireNonNull(node, "node");
        StringBuilder builder = new StringBuilder();
        if (isInline(node)) {
            appendInline(builder, node);
        } else {
            appendBlock(builder, node, 0, false, new IdentityHashMap<>());
        }
        if (!builder.isEmpty() && builder.charAt(builder.length() - 1) == '\n') {
            builder.setLength(builder.length() - 1);
        }
        return builder.toString();
    }

    private void appendBlock(
        StringBuilder builder,
        YamlNode node,
        int column,
        boolean continueLine,
        IdentityHashMap<YamlNode, Boolean> activeNodes
    ) {
        ensureNoTagOrAnchor(node);
        if (activeNodes.put(node, Boolean.TRUE) != null) {
            throw new YamlWriteException("Cyclic YAML node graph detected");
        }
        try {
            if (node instanceof YamlSequence sequence) {
                appendSequence(builder, sequence, column, continueLine, activeNodes);
            } else if (node instanceof YamlMapping mapping) {
                appendMapping(builder, mapping, column, continueLine, activeNodes);
            } else {
                throw new YamlWriteException("Unsupported YAML node: " + node.getClass().getName());
            }
        } finally {
            activeNodes.remove(node);
        }
    }

    private void appendSequence(
        StringBuilder builder,
        YamlSequence sequence,
        int column,
        boolean continueLine,
        IdentityHashMap<YamlNode, Boolean> activeNodes
    ) {
        boolean first = true;
        for (YamlNode item : sequence.values()) {
            if (!first || !continueLine) {
                indent(builder, column);
            }
            first = false;
            builder.append("- ");
            if (isInline(item)) {
                appendInline(builder, item);
                builder.append('\n');
            } else {
                appendBlock(builder, item, column + 2, true, activeNodes);
            }
        }
    }

    private void appendMapping(
        StringBuilder builder,
        YamlMapping mapping,
        int column,
        boolean continueLine,
        IdentityHashMap<YamlNode, Boolean> activeNodes
    ) {
        boolean first = true;
        for (YamlEntry entry : mapping.entries()) {
            if (!first || !continueLine) {
                indent(builder, column);
            }
            first = false;
            appendKey(builder, entry.key());
            builder.append(':');
            if (isInline(entry.value())) {
                builder.append(' ');
                appendInline(builder, entry.value());
                builder.append('\n');
            } else {
                builder.append('\n');
                appendBlock(builder, entry.value(), column + indentUnit.length(), false, activeNodes);
            }
        }
    }

    private void appendKey(StringBuilder builder, YamlNode key) {
        if (!(key instanceof YamlScalar scalar)) {
            throw new YamlWriteException("Current writer supports only scalar mapping keys");
        }
        ensureNoTagOrAnchor(scalar);
        appendScalar(builder, scalar);
    }

    private static boolean isInline(YamlNode node) {
        return node instanceof YamlScalar
            || node instanceof YamlAlias
            || node instanceof YamlSequence sequence && sequence.size() == 0
            || node instanceof YamlMapping mapping && mapping.size() == 0;
    }

    private void appendInline(StringBuilder builder, YamlNode node) {
        ensureNoTagOrAnchor(node);
        switch (node) {
            case YamlScalar scalar -> appendScalar(builder, scalar);
            case YamlAlias alias -> appendAlias(builder, alias);
            case YamlSequence ignored -> builder.append("[]");
            case YamlMapping ignored -> builder.append("{}");
        }
    }

    private void appendAlias(StringBuilder builder, YamlAlias alias) {
        String name = alias.alias();
        if (name == null || name.isEmpty() || name.chars().anyMatch(ch -> Character.isWhitespace(ch) || ",[]{}".indexOf(ch) >= 0)) {
            throw new YamlWriteException("Invalid alias name: " + name);
        }
        builder.append('*').append(name);
    }

    private void appendScalar(StringBuilder builder, YamlScalar scalar) {
        String value = scalar.value();
        if (value == null) {
            builder.append("null");
            return;
        }
        switch (scalar.style()) {
            case DOUBLE_QUOTED -> appendDoubleQuoted(builder, value);
            case SINGLE_QUOTED -> {
                if (value.codePoints().anyMatch(YamlWriter::needsEscape)) {
                    appendDoubleQuoted(builder, value);
                } else {
                    builder.append('\'').append(value.replace("'", "''")).append('\'');
                }
            }
            case PLAIN -> {
                if (requiresQuoting(value)) {
                    appendDoubleQuoted(builder, value);
                } else {
                    builder.append(value);
                }
            }
        }
    }

    private static boolean requiresQuoting(String value) {
        if (value.isEmpty()) {
            return true;
        }
        char first = value.charAt(0);
        if (isBlank(first) || isBlank(value.charAt(value.length() - 1))) {
            return true;
        }
        if (value.codePoints().anyMatch(YamlWriter::needsEscape)) {
            return true;
        }
        if (PLAIN_FORBIDDEN_FIRST.indexOf(first) >= 0) {
            return true;
        }
        if ((first == '-' || first == '?' || first == ':') && (value.length() == 1 || isBlank(value.charAt(1)))) {
            return true;
        }
        if (value.startsWith("---") || value.startsWith("...")) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == ':' && (i + 1 == value.length() || isBlank(value.charAt(i + 1)))) {
                return true;
            }
            if (ch == '#' && isBlank(value.charAt(i - 1))) {
                return true;
            }
        }
        return false;
    }

    private static void appendDoubleQuoted(StringBuilder builder, String value) {
        builder.append('"');
        value.codePoints().forEach(codePoint -> {
            switch (codePoint) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case 0 -> builder.append("\\0");
                case 0x07 -> builder.append("\\a");
                case '\b' -> builder.append("\\b");
                case '\t' -> builder.append("\\t");
                case '\n' -> builder.append("\\n");
                case 0x0B -> builder.append("\\v");
                case '\f' -> builder.append("\\f");
                case '\r' -> builder.append("\\r");
                case 0x1B -> builder.append("\\e");
                case 0x85 -> builder.append("\\N");
                case 0x2028 -> builder.append("\\L");
                case 0x2029 -> builder.append("\\P");
                default -> {
                    if (!needsEscape(codePoint)) {
                        builder.appendCodePoint(codePoint);
                    } else if (codePoint <= 0xFF) {
                        builder.append(String.format("\\x%02X", codePoint));
                    } else if (codePoint <= 0xFFFF) {
                        builder.append(String.format("\\u%04X", codePoint));
                    } else {
                        builder.append(String.format("\\U%08X", codePoint));
                    }
                }
            }
        });
        builder.append('"');
    }

    /**
     * Characters that cannot appear literally on a single line of a YAML scalar:
     * line breaks, tabs, non-printable characters, unpaired surrogates and the byte order mark.
     */
    private static boolean needsEscape(int codePoint) {
        if (codePoint == '\t' || codePoint == '\n' || codePoint == '\r' || codePoint == 0x85 || codePoint == 0xFEFF) {
            return true;
        }
        if (codePoint == 0x2028 || codePoint == 0x2029) {
            return true;
        }
        boolean printable = codePoint >= 0x20 && codePoint <= 0x7E
            || codePoint >= 0xA0 && codePoint <= 0xD7FF
            || codePoint >= 0xE000 && codePoint <= 0xFFFD
            || codePoint >= 0x10000 && codePoint <= 0x10FFFF;
        return !printable;
    }

    private static void ensureNoTagOrAnchor(YamlNode node) {
        if (node.tag() != null || node.anchor() != null) {
            throw new YamlWriteException("Tags and anchors are not supported by the writer");
        }
    }

    private static boolean isBlank(char ch) {
        return ch == ' ' || ch == '\t';
    }

    private static void indent(StringBuilder builder, int column) {
        builder.repeat(' ', column);
    }
}
