package io.pure.yaml.core.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.pure.yaml.core.model.YamlAlias;
import io.pure.yaml.core.model.YamlDocument;
import io.pure.yaml.core.model.YamlMapping;
import io.pure.yaml.core.model.YamlNode;
import io.pure.yaml.core.model.YamlScalar;
import io.pure.yaml.core.model.YamlScalarStyle;
import io.pure.yaml.core.model.YamlSequence;
import io.pure.yaml.core.model.YamlStream;

public final class YamlParser {
    private final List<Line> lines;
    private final YamlProcessingLimits limits;
    private int index;
    private int aliasCount;

    public YamlParser(String text, YamlProcessingLimits limits) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(limits, "limits");
        if (text.length() > limits.maxCharacters()) {
            throw new YamlParseException("YAML input exceeds configured size limit");
        }
        this.limits = limits;
        this.lines = tokenize(text);
    }

    public YamlDocument parse() {
        YamlDocument document = parseDocument();
        if (!atEnd()) {
            throw new YamlParseException(
                "Multi-document YAML is not supported by parse(), use parseStream() (line " + current().lineNumber() + ")"
            );
        }
        return document;
    }

    public YamlStream parseStream() {
        YamlStream stream = new YamlStream();
        do {
            if (stream.documents().size() >= limits.maxDocuments()) {
                throw new YamlParseException("YAML document count exceeds configured limit");
            }
            stream.add(parseDocument());
        } while (!atEnd());
        return stream;
    }

    private YamlDocument parseDocument() {
        String versionDirective = parseDirectives();
        skipIgnorable();
        if (index < lines.size() && isDocumentStart(current())) {
            ensureNothingAfterMarker(current());
            index++;
        } else if (versionDirective != null) {
            throw new YamlParseException("Directives must be followed by a document start marker '---'");
        }
        YamlNode root = parseBlockValue(-1, 0, false);
        skipIgnorable();
        if (index < lines.size()) {
            Line line = current();
            if (isDocumentEnd(line)) {
                ensureNothingAfterMarker(line);
                index++;
            } else if (!isDocumentStart(line)) {
                throw new YamlParseException("Unexpected content at line " + line.lineNumber());
            }
        }
        return new YamlDocument(root, versionDirective);
    }

    private String parseDirectives() {
        String versionDirective = null;
        while (true) {
            skipIgnorable();
            if (index >= lines.size()) {
                return versionDirective;
            }
            Line line = current();
            if (line.indent() != 0 || !line.content().startsWith("%")) {
                return versionDirective;
            }
            String directive = stripComment(line.content());
            String[] parts = directive.split("[ \t]+");
            if (parts[0].equals("%YAML")) {
                if (versionDirective != null) {
                    throw new YamlParseException("Duplicate %YAML directive at line " + line.lineNumber());
                }
                if (parts.length != 2 || !parts[1].matches("1\\.[0-9]+")) {
                    throw new YamlParseException("Unsupported YAML version directive at line " + line.lineNumber() + ": " + directive);
                }
                versionDirective = directive;
            } else if (parts[0].equals("%TAG")) {
                throw new YamlParseException("%TAG directives are not supported at line " + line.lineNumber());
            }
            // Other directives are reserved by the YAML spec and ignored.
            index++;
        }
    }

    private YamlNode parseBlockValue(int parentIndent, int depth, boolean allowCompactSequence) {
        skipIgnorable();
        if (index >= lines.size()) {
            return emptyNode();
        }
        Line line = current();
        if (isDocumentMarker(line)) {
            return emptyNode();
        }
        if (line.indent() > parentIndent) {
            return parseNodeAt(line, depth);
        }
        if (allowCompactSequence && line.indent() == parentIndent && isSequenceEntry(line.content())) {
            return parseSequence(parentIndent, depth + 1);
        }
        return emptyNode();
    }

    private YamlNode parseNodeAt(Line line, int depth) {
        if (isSequenceEntry(line.content())) {
            return parseSequence(line.indent(), depth + 1);
        }
        if (findKeySeparator(line.content()) >= 0) {
            return parseMapping(line.indent(), depth + 1);
        }
        index++;
        return parseInlineValue(line.content(), line.lineNumber(), false);
    }

    private YamlSequence parseSequence(int indent, int depth) {
        ensureDepth(depth);
        YamlSequence sequence = new YamlSequence();
        while (true) {
            skipIgnorable();
            if (index >= lines.size()) {
                break;
            }
            Line line = current();
            if (line.indent() < indent || isDocumentMarker(line)) {
                break;
            }
            if (line.indent() > indent) {
                throw invalidIndentation(line);
            }
            String content = line.content();
            if (!isSequenceEntry(content)) {
                break;
            }
            int offset = 1;
            while (offset < content.length() && isBlank(content.charAt(offset))) {
                offset++;
            }
            String rest = content.substring(offset);
            if (rest.isEmpty() || rest.startsWith("#")) {
                index++;
                sequence.add(parseBlockValue(indent, depth, false));
            } else {
                // Re-read the entry content as if it started its own line at the column after "- ".
                Line item = new Line(line.lineNumber(), indent + offset, rest, false);
                lines.set(index, item);
                sequence.add(parseNodeAt(item, depth));
            }
        }
        return sequence;
    }

    private YamlMapping parseMapping(int indent, int depth) {
        ensureDepth(depth);
        YamlMapping mapping = new YamlMapping();
        while (true) {
            skipIgnorable();
            if (index >= lines.size()) {
                break;
            }
            Line line = current();
            if (line.indent() < indent || isDocumentMarker(line)) {
                break;
            }
            if (line.indent() > indent) {
                throw invalidIndentation(line);
            }
            String content = line.content();
            if (isSequenceEntry(content)) {
                throw new YamlParseException("Sequence entry is not allowed inside a mapping at line " + line.lineNumber());
            }
            int separator = findKeySeparator(content);
            if (separator < 0) {
                throw new YamlParseException("Expected a mapping entry at line " + line.lineNumber());
            }
            index++;
            YamlScalar key = parseKey(content.substring(0, separator), line.lineNumber());
            if (mapping.containsKey(key.value())) {
                throw new YamlParseException("Duplicate mapping key '" + key.value() + "' at line " + line.lineNumber());
            }
            String rawValue = trim(content.substring(separator + 1));
            YamlNode value = rawValue.isEmpty() || rawValue.startsWith("#")
                ? parseBlockValue(indent, depth, true)
                : parseInlineValue(rawValue, line.lineNumber(), false);
            mapping.put(key, value);
        }
        return mapping;
    }

    private YamlScalar parseKey(String rawKey, int lineNumber) {
        String key = trim(rawKey);
        if (key.isEmpty()) {
            throw new YamlParseException("Empty mapping keys are not supported at line " + lineNumber);
        }
        if (isIndicatorWithBlank(key, '?')) {
            throw new YamlParseException("Complex mapping keys are not supported at line " + lineNumber);
        }
        if (key.charAt(0) == '*') {
            throw new YamlParseException("Alias mapping keys are not supported at line " + lineNumber);
        }
        if (!(parseInlineValue(key, lineNumber, true) instanceof YamlScalar scalar)) {
            throw new YamlParseException("Only scalar mapping keys are supported at line " + lineNumber);
        }
        return scalar;
    }

    private YamlNode parseInlineValue(String raw, int lineNumber, boolean key) {
        char first = raw.charAt(0);
        if (first == '"' || first == '\'') {
            return parseQuoted(raw, lineNumber);
        }
        String text = stripComment(raw);
        switch (first) {
            case '*' -> {
                return parseAlias(text, lineNumber);
            }
            case '[', '{' -> {
                return parseFlowCollection(text, lineNumber);
            }
            case '&' -> throw new YamlParseException("Anchors are not supported at line " + lineNumber);
            case '!' -> throw new YamlParseException("Tags are not supported at line " + lineNumber);
            case '|', '>' -> throw new YamlParseException("Block scalars ('|', '>') are not supported at line " + lineNumber);
            case ']', '}', ',', '%', '@', '`' ->
                throw new YamlParseException("Plain scalar cannot start with '" + first + "' at line " + lineNumber);
            default -> {
            }
        }
        if (isSequenceEntry(text)) {
            throw new YamlParseException("Sequence entry is not allowed on a mapping value line at line " + lineNumber);
        }
        if (isIndicatorWithBlank(text, '?')) {
            throw new YamlParseException("Complex mapping keys are not supported at line " + lineNumber);
        }
        if (!key && hasMappingIndicator(text)) {
            throw new YamlParseException("Mapping values are not allowed here at line " + lineNumber + ", quote the value");
        }
        return YamlScalar.plain(text);
    }

    private YamlScalar parseQuoted(String raw, int lineNumber) {
        boolean doubleQuoted = raw.charAt(0) == '"';
        int end = doubleQuoted ? findDoubleQuotedEnd(raw) : findSingleQuotedEnd(raw);
        if (end < 0) {
            throw new YamlParseException(
                "Unterminated " + (doubleQuoted ? "double" : "single") + "-quoted scalar at line " + lineNumber
            );
        }
        String trailing = raw.substring(end + 1);
        if (!trim(trailing).isEmpty() && !(isBlank(trailing.charAt(0)) && trim(trailing).startsWith("#"))) {
            throw new YamlParseException("Unexpected content after quoted scalar at line " + lineNumber);
        }
        String body = raw.substring(1, end);
        return doubleQuoted
            ? new YamlScalar(unescape(body, lineNumber), null, null, YamlScalarStyle.DOUBLE_QUOTED)
            : new YamlScalar(body.replace("''", "'"), null, null, YamlScalarStyle.SINGLE_QUOTED);
    }

    private YamlAlias parseAlias(String text, int lineNumber) {
        String name = text.substring(1);
        if (name.isEmpty() || name.chars().anyMatch(ch -> Character.isWhitespace(ch) || ",[]{}".indexOf(ch) >= 0)) {
            throw new YamlParseException("Invalid alias at line " + lineNumber + ": " + text);
        }
        aliasCount++;
        if (aliasCount > limits.maxAliases()) {
            throw new YamlParseException("Alias limit exceeded at line " + lineNumber);
        }
        return new YamlAlias(name);
    }

    private YamlNode parseFlowCollection(String text, int lineNumber) {
        if (text.matches("\\[[ \t]*]")) {
            return new YamlSequence();
        }
        if (text.matches("\\{[ \t]*}")) {
            return new YamlMapping();
        }
        throw new YamlParseException("Flow collections are not supported (only empty [] and {}) at line " + lineNumber);
    }

    private String unescape(String body, int lineNumber) {
        StringBuilder builder = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch != '\\') {
                builder.append(ch);
                continue;
            }
            if (i + 1 >= body.length()) {
                throw new YamlParseException("Invalid escape at line " + lineNumber);
            }
            char escaped = body.charAt(++i);
            switch (escaped) {
                case '0' -> builder.append('\0');
                case 'a' -> builder.append('\u0007');
                case 'b' -> builder.append('\b');
                case 't', '\t' -> builder.append('\t');
                case 'n' -> builder.append('\n');
                case 'v' -> builder.append('\u000B');
                case 'f' -> builder.append('\f');
                case 'r' -> builder.append('\r');
                case 'e' -> builder.append('\u001B');
                case ' ' -> builder.append(' ');
                case '"' -> builder.append('"');
                case '/' -> builder.append('/');
                case '\\' -> builder.append('\\');
                case 'N' -> builder.append('\u0085');
                case '_' -> builder.append(' ');
                case 'L' -> builder.append(' ');
                case 'P' -> builder.append(' ');
                case 'x' -> i = appendHexEscape(builder, body, i, 2, lineNumber);
                case 'u' -> i = appendHexEscape(builder, body, i, 4, lineNumber);
                case 'U' -> i = appendHexEscape(builder, body, i, 8, lineNumber);
                default -> throw new YamlParseException("Unsupported escape \\" + escaped + " at line " + lineNumber);
            }
        }
        return builder.toString();
    }

    private int appendHexEscape(StringBuilder builder, String body, int position, int digits, int lineNumber) {
        if (position + digits >= body.length()) {
            throw new YamlParseException("Truncated escape sequence at line " + lineNumber);
        }
        String hex = body.substring(position + 1, position + 1 + digits);
        int codePoint;
        try {
            codePoint = Integer.parseUnsignedInt(hex, 16);
        } catch (NumberFormatException exception) {
            throw new YamlParseException("Invalid escape sequence at line " + lineNumber + ": " + hex);
        }
        if (!Character.isValidCodePoint(codePoint) || !hex.chars().allMatch(ch -> Character.digit(ch, 16) >= 0)) {
            throw new YamlParseException("Invalid escape sequence at line " + lineNumber + ": " + hex);
        }
        builder.appendCodePoint(codePoint);
        return position + digits;
    }

    private void skipIgnorable() {
        while (index < lines.size() && lines.get(index).ignorable()) {
            index++;
        }
    }

    private boolean atEnd() {
        skipIgnorable();
        return index >= lines.size();
    }

    private Line current() {
        return lines.get(index);
    }

    private void ensureDepth(int depth) {
        if (depth > limits.maxDepth()) {
            throw new YamlParseException("YAML nesting depth exceeds configured limit");
        }
    }

    private void ensureNothingAfterMarker(Line line) {
        if (!stripComment(line.content().substring(3)).isEmpty()) {
            throw new YamlParseException("Content on a document marker line is not supported at line " + line.lineNumber());
        }
    }

    private static YamlParseException invalidIndentation(Line line) {
        return new YamlParseException("Invalid indentation at line " + line.lineNumber());
    }

    private static YamlScalar emptyNode() {
        return YamlScalar.plain(null);
    }

    private static boolean isDocumentMarker(Line line) {
        return isDocumentStart(line) || isDocumentEnd(line);
    }

    private static boolean isDocumentStart(Line line) {
        return isMarker(line, "---");
    }

    private static boolean isDocumentEnd(Line line) {
        return isMarker(line, "...");
    }

    private static boolean isMarker(Line line, String marker) {
        String content = line.content();
        return line.indent() == 0
            && content.startsWith(marker)
            && (content.length() == marker.length() || isBlank(content.charAt(marker.length())));
    }

    private static boolean isSequenceEntry(String content) {
        return isIndicatorWithBlank(content, '-');
    }

    private static boolean isIndicatorWithBlank(String content, char indicator) {
        return !content.isEmpty()
            && content.charAt(0) == indicator
            && (content.length() == 1 || isBlank(content.charAt(1)));
    }

    private static int findKeySeparator(String content) {
        if (content.isEmpty()) {
            return -1;
        }
        char first = content.charAt(0);
        if (first == '"' || first == '\'') {
            int end = first == '"' ? findDoubleQuotedEnd(content) : findSingleQuotedEnd(content);
            if (end < 0) {
                return -1;
            }
            int i = end + 1;
            while (i < content.length() && isBlank(content.charAt(i))) {
                i++;
            }
            return isMappingIndicatorAt(content, i) ? i : -1;
        }
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '#' && i > 0 && isBlank(content.charAt(i - 1))) {
                return -1;
            }
            if (isMappingIndicatorAt(content, i)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hasMappingIndicator(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (isMappingIndicatorAt(text, i)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMappingIndicatorAt(String text, int i) {
        return i < text.length()
            && text.charAt(i) == ':'
            && (i + 1 == text.length() || isBlank(text.charAt(i + 1)));
    }

    private static int findDoubleQuotedEnd(String text) {
        for (int i = 1; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\\') {
                i++;
            } else if (ch == '"') {
                return i;
            }
        }
        return -1;
    }

    private static int findSingleQuotedEnd(String text) {
        for (int i = 1; i < text.length(); i++) {
            if (text.charAt(i) == '\'') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String stripComment(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '#' && (i == 0 || isBlank(text.charAt(i - 1)))) {
                return trim(text.substring(0, i));
            }
        }
        return trim(text);
    }

    private static String trim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isBlank(text.charAt(start))) {
            start++;
        }
        while (end > start && isBlank(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isBlank(char ch) {
        return ch == ' ' || ch == '\t';
    }

    private static List<Line> tokenize(String text) {
        int start = text.startsWith("﻿") ? 1 : 0;
        List<Line> result = new ArrayList<>();
        int lineNumber = 1;
        int lineStart = start;
        for (int i = start; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n' || text.charAt(i) == '\r') {
                result.add(parseLine(text.substring(lineStart, i), lineNumber++));
                if (i < text.length() && text.charAt(i) == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                lineStart = i + 1;
            }
        }
        return result;
    }

    private static Line parseLine(String raw, int lineNumber) {
        int indent = 0;
        while (indent < raw.length() && raw.charAt(indent) == ' ') {
            indent++;
        }
        String rest = raw.substring(indent);
        String content = trim(rest);
        if (!rest.isEmpty() && rest.charAt(0) == '\t' && !content.isEmpty() && !content.startsWith("#")) {
            throw new YamlParseException("Tabs are not supported for indentation at line " + lineNumber);
        }
        return new Line(lineNumber, indent, content, content.isEmpty() || content.startsWith("#"));
    }

    private record Line(int lineNumber, int indent, String content, boolean ignorable) {
    }
}
