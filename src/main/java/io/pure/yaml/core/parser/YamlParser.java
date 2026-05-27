package io.pure.yaml.core.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.pure.yaml.core.model.YamlAlias;
import io.pure.yaml.core.model.YamlDocument;
import io.pure.yaml.core.model.YamlMapping;
import io.pure.yaml.core.model.YamlNode;
import io.pure.yaml.core.model.YamlScalar;
import io.pure.yaml.core.model.YamlScalarStyle;
import io.pure.yaml.core.model.YamlSequence;

public final class YamlParser {
    private static final Set<Character> TRIMMED_ESCAPE_CODES = Set.of('n', 'r', 't', '"', '\\');

    private final List<Line> lines;
    private final YamlProcessingLimits limits;
    private int index;
    private int documentCount;
    private int aliasCount;
    private String versionDirective;

    public YamlParser(String text, YamlProcessingLimits limits) {
        if (text.length() > limits.maxCharacters()) {
            throw new YamlParseException("YAML input exceeds configured size limit");
        }
        this.lines = tokenize(text);
        this.limits = limits;
    }

    public YamlDocument parse() {
        skipMetadata();
        if (peekContent("---")) {
            documentCount++;
            ensureDocumentsWithinLimit();
            index++;
        }
        YamlNode root = parseNode(0, 0);
        skipTrailing();
        if (peekContent("---")) {
            throw new YamlParseException("Multi-document YAML is not enabled in this parser");
        }
        return new YamlDocument(root, versionDirective);
    }

    private YamlNode parseNode(int indent, int depth) {
        ensureDepth(depth);
        skipIgnorable();
        if (index >= lines.size()) {
            throw new YamlParseException("Unexpected end of YAML input");
        }
        Line current = lines.get(index);
        if (current.indent() < indent) {
            throw new YamlParseException("Unexpected indentation at line " + current.lineNumber());
        }
        if (current.indent() != indent) {
            throw new YamlParseException("Invalid indentation at line " + current.lineNumber());
        }
        if (current.content().startsWith("- ")) {
            return parseSequence(indent, depth + 1);
        }
        if (isMappingLine(current.content())) {
            return parseMapping(indent, depth + 1);
        }
        index++;
        return parseScalar(current.content(), current.lineNumber());
    }

    private YamlSequence parseSequence(int indent, int depth) {
        YamlSequence sequence = new YamlSequence();
        while (index < lines.size()) {
            Line line = lines.get(index);
            if (line.indent() != indent || !line.content().startsWith("- ")) {
                break;
            }
            String remainder = line.content().substring(2).trim();
            index++;
            if (remainder.isEmpty()) {
                sequence.add(parseNode(indent + 2, depth));
                continue;
            }
            if (isMappingLine(remainder)) {
                sequence.add(parseSequenceItemMapping(remainder, line.lineNumber(), indent + 2, depth));
                continue;
            }
            if (looksLikeNestedCollection(remainder)) {
                sequence.add(parseInlineCollectionOrScalar(remainder, line.lineNumber(), indent + 2, depth));
                continue;
            }
            sequence.add(parseScalar(remainder, line.lineNumber()));
        }
        return sequence;
    }

    private YamlMapping parseMapping(int indent, int depth) {
        YamlMapping mapping = new YamlMapping();
        while (index < lines.size()) {
            Line line = lines.get(index);
            if (line.indent() != indent || !isMappingLine(line.content())) {
                break;
            }
            int colonIndex = findKeySeparator(line.content());
            String rawKey = line.content().substring(0, colonIndex).trim();
            String rawValue = line.content().substring(colonIndex + 1).trim();
            index++;
            YamlNode key = parseKey(rawKey, line.lineNumber());
            YamlNode value = rawValue.isEmpty()
                ? parseNode(indent + 2, depth)
                : parseInlineCollectionOrScalar(rawValue, line.lineNumber(), indent + 2, depth);
            mapping.put(key, value);
        }
        return mapping;
    }

    private YamlMapping parseSequenceItemMapping(String firstLineContent, int lineNumber, int indent, int depth) {
        YamlMapping mapping = new YamlMapping();
        addMappingEntry(mapping, firstLineContent, lineNumber, indent, depth);
        while (index < lines.size()) {
            Line next = lines.get(index);
            if (next.indent() != indent || !isMappingLine(next.content())) {
                break;
            }
            index++;
            addMappingEntry(mapping, next.content(), next.lineNumber(), indent, depth);
        }
        return mapping;
    }

    private void addMappingEntry(YamlMapping mapping, String content, int lineNumber, int indent, int depth) {
        int colonIndex = findKeySeparator(content);
        String rawKey = content.substring(0, colonIndex).trim();
        String rawValue = content.substring(colonIndex + 1).trim();
        YamlNode key = parseKey(rawKey, lineNumber);
        YamlNode value = rawValue.isEmpty()
            ? parseNode(indent + 2, depth)
            : parseInlineCollectionOrScalar(rawValue, lineNumber, indent, depth);
        mapping.put(key, value);
    }

    private YamlNode parseInlineCollectionOrScalar(String rawValue, int lineNumber, int indent, int depth) {
        if (rawValue.startsWith("[") || rawValue.startsWith("{")) {
            throw new YamlParseException("Flow collections are reserved for a later parser phase at line " + lineNumber);
        }
        if (looksLikeSequenceEntry(rawValue)) {
            throw new YamlParseException("Inline nested sequences are not supported at line " + lineNumber);
        }
        return parseScalar(rawValue, lineNumber);
    }

    private YamlNode parseKey(String rawKey, int lineNumber) {
        YamlNode key = parseScalar(rawKey, lineNumber);
        if (!(key instanceof YamlScalar)) {
            throw new YamlParseException("Only scalar mapping keys are supported by the current parser at line " + lineNumber);
        }
        return key;
    }

    private YamlNode parseScalar(String raw, int lineNumber) {
        if (raw.startsWith("*")) {
            aliasCount++;
            if (aliasCount > limits.maxAliases()) {
                throw new YamlParseException("Alias limit exceeded at line " + lineNumber);
            }
            return new YamlAlias(raw.substring(1));
        }
        if (raw.startsWith("\"")) {
            return new YamlScalar(parseDoubleQuoted(raw, lineNumber), null, null, YamlScalarStyle.DOUBLE_QUOTED);
        }
        if (raw.startsWith("'")) {
            return new YamlScalar(parseSingleQuoted(raw, lineNumber), null, null, YamlScalarStyle.SINGLE_QUOTED);
        }
        return YamlScalar.plain(raw);
    }

    private String parseDoubleQuoted(String raw, int lineNumber) {
        if (raw.length() < 2 || !raw.endsWith("\"")) {
            throw new YamlParseException("Unterminated double-quoted scalar at line " + lineNumber);
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 1; i < raw.length() - 1; i++) {
            char ch = raw.charAt(i);
            if (ch == '\\') {
                if (i + 1 >= raw.length() - 1) {
                    throw new YamlParseException("Invalid escape at line " + lineNumber);
                }
                char escaped = raw.charAt(++i);
                if (!TRIMMED_ESCAPE_CODES.contains(escaped)) {
                    throw new YamlParseException("Unsupported escape \\" + escaped + " at line " + lineNumber);
                }
                builder.append(switch (escaped) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '"' -> '"';
                    default -> '\\';
                });
                continue;
            }
            builder.append(ch);
        }
        return builder.toString();
    }

    private String parseSingleQuoted(String raw, int lineNumber) {
        if (raw.length() < 2 || !raw.endsWith("'")) {
            throw new YamlParseException("Unterminated single-quoted scalar at line " + lineNumber);
        }
        return raw.substring(1, raw.length() - 1).replace("''", "'");
    }

    private void skipMetadata() {
        while (index < lines.size()) {
            Line line = lines.get(index);
            if (line.content().startsWith("%YAML")) {
                versionDirective = line.content();
                index++;
                continue;
            }
            if (line.ignorable()) {
                index++;
                continue;
            }
            break;
        }
    }

    private void skipTrailing() {
        while (index < lines.size()) {
            Line line = lines.get(index);
            if (line.content().equals("...") || line.ignorable()) {
                index++;
                continue;
            }
            break;
        }
    }

    private void skipIgnorable() {
        while (index < lines.size() && lines.get(index).ignorable()) {
            index++;
        }
    }

    private boolean peekContent(String value) {
        return index < lines.size() && lines.get(index).content().equals(value);
    }

    private void ensureDepth(int depth) {
        if (depth > limits.maxDepth()) {
            throw new YamlParseException("YAML nesting depth exceeds configured limit");
        }
    }

    private void ensureDocumentsWithinLimit() {
        if (documentCount > limits.maxDocuments()) {
            throw new YamlParseException("YAML document count exceeds configured limit");
        }
    }

    private boolean isMappingLine(String content) {
        int separator = findKeySeparator(content);
        return separator > 0;
    }

    private int findKeySeparator(String content) {
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
            } else if (ch == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
            } else if (ch == ':' && !singleQuoted && !doubleQuoted) {
                if (i + 1 == content.length() || Character.isWhitespace(content.charAt(i + 1))) {
                    return i;
                }
            }
        }
        return -1;
    }

    private boolean looksLikeNestedCollection(String value) {
        return value.startsWith("- ") || value.contains(": ");
    }

    private boolean looksLikeSequenceEntry(String value) {
        return value.startsWith("- ");
    }

    private static List<Line> tokenize(String text) {
        int start = text.startsWith("\uFEFF") ? 1 : 0;
        List<Line> result = new ArrayList<>();
        int lineNumber = 1;
        int lineStart = start;
        for (int i = start; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n' || text.charAt(i) == '\r') {
                result.add(parseLine(text, lineStart, i, lineNumber++));
                if (i < text.length() && text.charAt(i) == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                lineStart = i + 1;
            }
        }
        return result;
    }

    private static Line parseLine(String text, int start, int end, int lineNumber) {
        int indent = countIndent(text, start, end);
        int contentStart = Math.min(start + indent, end);
        int commentStart = findCommentStart(text, contentStart, end);
        int trimmedEnd = trimTrailing(text, contentStart, commentStart);
        String content = text.substring(contentStart, trimmedEnd);
        return new Line(lineNumber, indent, content, content.isBlank());
    }

    private static int countIndent(String text, int start, int end) {
        int count = 0;
        while (start + count < end && text.charAt(start + count) == ' ') {
            count++;
        }
        if (start + count < end && text.charAt(start + count) == '\t') {
            throw new YamlParseException("Tabs are not supported for indentation");
        }
        return count;
    }

    private static int findCommentStart(String text, int start, int end) {
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int i = start; i < end; i++) {
            char ch = text.charAt(i);
            if (ch == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
            } else if (ch == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
            } else if (ch == '#' && !singleQuoted && !doubleQuoted) {
                if (i == start || Character.isWhitespace(text.charAt(i - 1))) {
                    return i;
                }
            }
        }
        return end;
    }

    private static int trimTrailing(String text, int start, int end) {
        int result = end;
        while (result > start && Character.isWhitespace(text.charAt(result - 1))) {
            result--;
        }
        return result;
    }

    private record Line(int lineNumber, int indent, String content, boolean ignorable) {
    }
}
