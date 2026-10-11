package io.pure.yaml.core.writer;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

import io.pure.yaml.core.model.YamlAlias;
import io.pure.yaml.core.model.YamlDocument;
import io.pure.yaml.core.model.YamlEntry;
import io.pure.yaml.core.model.YamlMapping;
import io.pure.yaml.core.model.YamlNode;
import io.pure.yaml.core.model.YamlScalar;
import io.pure.yaml.core.model.YamlScalarStyle;
import io.pure.yaml.core.model.YamlSequence;
import io.pure.yaml.core.model.YamlStream;
import io.pure.yaml.core.model.YamlTags;

public final class YamlWriter {
    private static final String PLAIN_FORBIDDEN_FIRST = ",[]{}#&*!|>'\"%@`";
    private static final int MAX_IMPLICIT_KEY_LENGTH = 1024;
    private static final String URI_SAFE_PUNCTUATION = "-#;/?:@&=+$_.~*'()";

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

    /**
     * Writes one node as a document without markers. An empty node (a scalar with a {@code null}
     * value) is written as an empty document.
     */
    public String write(YamlNode node) {
        Objects.requireNonNull(node, "node");
        StringBuilder builder = new StringBuilder();
        State state = new State();
        appendRoot(builder, node, state);
        return finish(builder, state);
    }

    /**
     * Writes a document; a {@code %YAML} directive is written together with the {@code ---} marker.
     */
    public String write(YamlDocument document) {
        Objects.requireNonNull(document, "document");
        StringBuilder builder = new StringBuilder();
        State state = new State();
        if (document.versionDirective() != null) {
            builder.append(versionDirective(document)).append("\n---\n");
        }
        appendRoot(builder, document.root(), state);
        return finish(builder, state);
    }

    /**
     * Writes every document of the stream after a {@code ---} marker; a document with a {@code %YAML}
     * directive that follows another document is preceded by a {@code ...} marker.
     */
    public String write(YamlStream stream) {
        Objects.requireNonNull(stream, "stream");
        StringBuilder builder = new StringBuilder();
        State state = new State();
        boolean first = true;
        for (YamlDocument document : stream.documents()) {
            if (document.versionDirective() != null) {
                if (!first) {
                    builder.append("...\n");
                }
                builder.append(versionDirective(document)).append('\n');
            }
            builder.append("---\n");
            // Anchors are scoped to their document.
            state = new State();
            appendRoot(builder, document.root(), state);
            first = false;
        }
        return finish(builder, state);
    }

    private void appendRoot(StringBuilder builder, YamlNode node, State state) {
        if (isInline(node)) {
            appendInline(builder, node, new Position(-1, indentUnit.length()), state);
            builder.append('\n');
        } else {
            if (appendProperties(builder, node, state)) {
                // Properties of a block collection stand on their own line.
                builder.setLength(builder.length() - 1);
                builder.append('\n');
            }
            appendBlock(builder, node, 0, false, state);
        }
    }

    /**
     * Removes the final line break, unless the output ends with a block scalar whose trailing
     * empty lines are part of its value.
     */
    private static String finish(StringBuilder builder, State state) {
        if (!state.endsWithKeptLines && !builder.isEmpty() && builder.charAt(builder.length() - 1) == '\n') {
            builder.setLength(builder.length() - 1);
        }
        return builder.toString();
    }

    private static String versionDirective(YamlDocument document) {
        String directive = document.versionDirective();
        if (!directive.matches("%YAML[ \t]+1\\.[0-9]+")) {
            throw new YamlWriteException("Unsupported YAML version directive: " + directive);
        }
        return directive;
    }

    private void appendBlock(StringBuilder builder, YamlNode node, int column, boolean continueLine, State state) {
        if (state.activeNodes.put(node, Boolean.TRUE) != null) {
            throw new YamlWriteException("Cyclic YAML node graph detected");
        }
        try {
            if (node instanceof YamlSequence sequence) {
                appendSequence(builder, sequence, column, continueLine, state);
            } else if (node instanceof YamlMapping mapping) {
                appendMapping(builder, mapping, column, continueLine, state);
            } else {
                throw new YamlWriteException("Unsupported YAML node: " + node.getClass().getName());
            }
        } finally {
            state.activeNodes.remove(node);
        }
    }

    private void appendSequence(StringBuilder builder, YamlSequence sequence, int column, boolean continueLine, State state) {
        boolean first = true;
        for (YamlNode item : sequence.values()) {
            if (!first || !continueLine) {
                indent(builder, column);
            }
            first = false;
            builder.append("- ");
            if (isInline(item)) {
                appendInline(builder, item, new Position(column, column + 2), state);
                builder.append('\n');
            } else if (appendProperties(builder, item, state)) {
                // "- &anchor !tag" followed by the collection on the next lines.
                builder.setLength(builder.length() - 1);
                builder.append('\n');
                appendBlock(builder, item, column + 2, false, state);
            } else {
                appendBlock(builder, item, column + 2, true, state);
            }
        }
    }

    private void appendMapping(StringBuilder builder, YamlMapping mapping, int column, boolean continueLine, State state) {
        boolean first = true;
        for (YamlEntry entry : mapping.entries()) {
            if (!first || !continueLine) {
                indent(builder, column);
            }
            first = false;
            String key = renderKey(entry.key(), state);
            if (key.codePointCount(0, key.length()) > MAX_IMPLICIT_KEY_LENGTH) {
                // Implicit keys are limited to 1024 characters (YAML 1.2.2, 7.4.2 and 8.2.1); longer keys
                // are written as explicit entries: "? key" followed by ": value".
                builder.append("? ").append(key).append('\n');
                indent(builder, column);
            } else {
                builder.append(key);
            }
            builder.append(':');
            if (isInline(entry.value())) {
                builder.append(' ');
                appendInline(builder, entry.value(), new Position(column, column + indentUnit.length()), state);
                builder.append('\n');
            } else {
                if (hasProperties(entry.value())) {
                    builder.append(' ');
                    appendProperties(builder, entry.value(), state);
                    builder.setLength(builder.length() - 1);
                }
                builder.append('\n');
                appendBlock(builder, entry.value(), column + indentUnit.length(), false, state);
            }
        }
    }

    private String renderKey(YamlNode key, State state) {
        state.endsWithKeptLines = false;
        StringBuilder rendered = new StringBuilder();
        if (key instanceof YamlAlias alias) {
            appendAlias(rendered, alias, state);
            // Anchor names may contain ':', so the separator must not touch the alias.
            rendered.append(' ');
        } else if (key instanceof YamlScalar scalar && scalar.value() == null) {
            // An empty key: only its properties, followed by white space before ':'.
            appendProperties(rendered, scalar, state);
        } else if (key instanceof YamlScalar scalar) {
            appendInline(rendered, scalar, null, state);
        } else {
            throw new YamlWriteException("Current writer supports only scalar mapping keys");
        }
        return rendered.toString();
    }

    private static boolean isInline(YamlNode node) {
        return node instanceof YamlScalar
            || node instanceof YamlAlias
            || node instanceof YamlSequence sequence && sequence.size() == 0
            || node instanceof YamlMapping mapping && mapping.size() == 0;
    }

    /**
     * Writes a node that starts on the current line.
     *
     * @param position where block scalar content goes, or {@code null} for mapping keys (single-line only)
     */
    private void appendInline(StringBuilder builder, YamlNode node, Position position, State state) {
        state.endsWithKeptLines = false;
        boolean withProperties = appendProperties(builder, node, state);
        switch (node) {
            case YamlScalar scalar -> {
                if (scalar.value() == null) {
                    // An empty node: nothing follows the indicator or the properties.
                    if (withProperties) {
                        builder.setLength(builder.length() - 1);
                    } else if (!builder.isEmpty() && builder.charAt(builder.length() - 1) == ' ') {
                        builder.setLength(builder.length() - 1);
                    }
                } else if (position == null || !appendLiteral(builder, scalar, position, state)) {
                    appendScalar(builder, scalar);
                }
            }
            case YamlAlias alias -> appendAlias(builder, alias, state);
            case YamlSequence ignored -> builder.append("[]");
            case YamlMapping ignored -> builder.append("{}");
        }
    }

    /**
     * Writes a multi-line value as a literal block scalar (YAML 1.2.2, 8.1.2) when its style allows a
     * block scalar and every character can appear literally.
     *
     * @return {@code false} when the value has to be written as a quoted scalar instead
     */
    private boolean appendLiteral(StringBuilder builder, YamlScalar scalar, Position position, State state) {
        String value = scalar.value();
        boolean blockStyle = scalar.style() == YamlScalarStyle.LITERAL || scalar.style() == YamlScalarStyle.FOLDED
            || scalar.style() == YamlScalarStyle.PLAIN;
        if (!blockStyle || value.indexOf('\n') < 0
            || !value.codePoints().allMatch(codePoint -> codePoint == '\n' || codePoint == '\t' || !needsEscape(codePoint))) {
            return false;
        }
        int trailingBreaks = 0;
        while (trailingBreaks < value.length() && value.charAt(value.length() - 1 - trailingBreaks) == '\n') {
            trailingBreaks++;
        }
        String body = value.substring(0, value.length() - trailingBreaks);
        if (body.isEmpty()) {
            return false;
        }
        String[] lines = body.split("\n", -1);
        String firstContent = "";
        for (String line : lines) {
            if (!line.isEmpty()) {
                firstContent = line;
                break;
            }
        }
        // Leading spaces on the first content line would be taken as indentation without an indicator.
        int indentation = position.contentColumn() - Math.max(position.parentIndent(), 0);
        boolean indicator = firstContent.startsWith(" ");
        if (indicator && (indentation < 1 || indentation > 9)) {
            return false;
        }
        builder.append('|');
        if (indicator) {
            builder.append(indentation);
        }
        if (trailingBreaks == 0) {
            builder.append('-');
        } else if (trailingBreaks > 1) {
            builder.append('+');
        }
        for (String line : lines) {
            builder.append('\n');
            if (!line.isEmpty()) {
                indent(builder, position.contentColumn());
                builder.append(line);
            }
        }
        if (trailingBreaks > 1) {
            builder.append("\n".repeat(trailingBreaks - 1));
            state.endsWithKeptLines = true;
        }
        return true;
    }

    private static boolean hasProperties(YamlNode node) {
        return node.anchor() != null || node.tag() != null;
    }

    /**
     * Writes "&amp;anchor !tag " for the properties the node has and records the anchor as defined.
     *
     * @return whether any property was written
     */
    private boolean appendProperties(StringBuilder builder, YamlNode node, State state) {
        if (!hasProperties(node)) {
            return false;
        }
        if (node instanceof YamlAlias) {
            throw new YamlWriteException("An alias cannot have an anchor or a tag");
        }
        String anchor = node.anchor();
        if (anchor != null) {
            ensureValidAnchorName(anchor);
            state.definedAnchors.add(anchor);
            builder.append('&').append(anchor).append(' ');
        }
        if (node.tag() != null) {
            builder.append(formatTag(node.tag())).append(' ');
        }
        return true;
    }

    /**
     * Writes a tag as {@code !!suffix} for core schema tags, {@code !suffix} for local tags, the
     * non-specific {@code !}, or verbatim {@code !<tag>} otherwise (YAML 1.2.2, 6.9.1).
     */
    private static String formatTag(String tag) {
        if (tag.equals(YamlTags.NON_SPECIFIC)) {
            return "!";
        }
        if (tag.startsWith(YamlTags.CORE_PREFIX) && isShorthandSuffix(tag.substring(YamlTags.CORE_PREFIX.length()))) {
            return "!!" + tag.substring(YamlTags.CORE_PREFIX.length());
        }
        if (tag.startsWith("!") && isShorthandSuffix(tag.substring(1))) {
            return tag;
        }
        if (tag.isEmpty() || tag.chars().anyMatch(ch -> ch == '<' || ch == '>' || ch <= ' ' || ch == 0x7F)) {
            throw new YamlWriteException("Tag cannot be written: " + tag);
        }
        return "!<" + tag + ">";
    }

    private static boolean isShorthandSuffix(String suffix) {
        return !suffix.isEmpty() && suffix.chars().allMatch(ch -> ch < 0x80
            && (Character.isLetterOrDigit(ch) || URI_SAFE_PUNCTUATION.indexOf(ch) >= 0));
    }

    private void appendAlias(StringBuilder builder, YamlAlias alias, State state) {
        String name = alias.alias();
        ensureValidAnchorName(name);
        if (!state.definedAnchors.contains(name)) {
            throw new YamlWriteException("Alias *" + name + " refers to an anchor that is not defined before it");
        }
        builder.append('*').append(name);
    }

    private static void ensureValidAnchorName(String name) {
        if (name == null || name.isEmpty() || name.chars().anyMatch(ch -> Character.isWhitespace(ch) || ",[]{}".indexOf(ch) >= 0)) {
            throw new YamlWriteException("Invalid anchor or alias name: " + name);
        }
    }

    private void appendScalar(StringBuilder builder, YamlScalar scalar) {
        String value = scalar.value();
        switch (scalar.style()) {
            // Block scalars that cannot be written as literal blocks keep their value as double-quoted scalars.
            case DOUBLE_QUOTED, LITERAL, FOLDED -> appendDoubleQuoted(builder, value);
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

    private static boolean isBlank(char ch) {
        return ch == ' ' || ch == '\t';
    }

    private static void indent(StringBuilder builder, int column) {
        builder.repeat(' ', column);
    }

    /**
     * Layout of a node's own lines.
     *
     * @param parentIndent  indentation of the enclosing collection (-1 for a document root)
     * @param contentColumn column of block scalar content lines
     */
    private record Position(int parentIndent, int contentColumn) {
    }

    /**
     * Per-document state: nodes on the current path (cycle detection), anchors written so far, and
     * whether the output ends with empty lines kept by a {@code |+} block scalar.
     */
    private static final class State {
        private final IdentityHashMap<YamlNode, Boolean> activeNodes = new IdentityHashMap<>();
        private final Set<String> definedAnchors = new HashSet<>();
        private boolean endsWithKeptLines;
    }
}
