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

/**
 * Parses one YAML text. An instance may be used for several {@code parse}/{@code parseStream} calls,
 * each of which starts from the beginning of the text; instances are not thread-safe.
 */
public final class YamlParser {
    private final String text;
    private final YamlProcessingLimits limits;
    private List<Line> lines;
    private int index;
    private int aliasCount;

    public YamlParser(String text, YamlProcessingLimits limits) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(limits, "limits");
        if (text.length() > limits.maxCharacters()) {
            throw new YamlParseException("YAML input exceeds configured size limit");
        }
        this.text = text;
        this.limits = limits;
    }

    /**
     * Parses a stream that contains at most one document; a stream without documents
     * (empty, or only comments) yields a document whose root is an empty node.
     */
    public YamlDocument parse() {
        reset();
        if (!skipToDocument()) {
            return new YamlDocument(emptyNode());
        }
        YamlDocument document = parseDocument();
        if (skipToDocument()) {
            throw new YamlParseException(
                "Multi-document YAML is not supported by parse(), use parseStream() (line " + current().lineNumber() + ")"
            );
        }
        return document;
    }

    /**
     * Parses all documents of the stream; a stream without documents yields an empty {@link YamlStream}.
     */
    public YamlStream parseStream() {
        reset();
        YamlStream stream = new YamlStream();
        while (skipToDocument()) {
            if (stream.documents().size() >= limits.maxDocuments()) {
                throw new YamlParseException("YAML document count exceeds configured limit");
            }
            stream.add(parseDocument());
        }
        return stream;
    }

    private void reset() {
        // Sequence entries rewrite lines in place, so every run needs a fresh copy.
        lines = tokenize(text);
        index = 0;
        aliasCount = 0;
    }

    /**
     * Skips comments and stray document end markers between documents.
     *
     * @return {@code true} when another document starts at the current line
     */
    private boolean skipToDocument() {
        allowByteOrderMark();
        while (true) {
            skipIgnorable();
            if (index >= lines.size()) {
                return false;
            }
            Line line = current();
            if (!isDocumentEnd(line)) {
                return true;
            }
            ensureNothingAfterMarker(line);
            index++;
            allowByteOrderMark();
        }
    }

    private YamlDocument parseDocument() {
        String versionDirective = parseDirectives();
        skipIgnorable();
        YamlNode root;
        if (index < lines.size() && isDocumentStart(current())) {
            root = parseExplicitDocumentRoot(current());
        } else if (versionDirective != null) {
            throw new YamlParseException("Directives must be followed by a document start marker '---'");
        } else {
            root = parseBlockValue(-1, 0, false);
        }
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

    private YamlNode parseExplicitDocumentRoot(Line marker) {
        String content = marker.content();
        int offset = 3;
        while (offset < content.length() && isBlank(content.charAt(offset))) {
            offset++;
        }
        String rest = content.substring(offset);
        if (rest.isEmpty() || rest.startsWith("#")) {
            index++;
            return parseBlockValue(-1, 0, false);
        }
        // "--- value": the root node starts on the marker line; only scalars may start there.
        if (isSequenceEntry(rest) || findKeySeparator(rest) >= 0) {
            throw new YamlParseException("Block collections cannot start on the document start line at line " + marker.lineNumber());
        }
        Line root = new Line(marker.lineNumber(), offset, rest, false, false, marker.trailing());
        lines.set(index, root);
        return parseNodeAt(root, -1, 0);
    }

    private String parseDirectives() {
        String versionDirective = null;
        while (true) {
            skipIgnorable();
            if (index >= lines.size()) {
                return versionDirective;
            }
            Line line = current();
            if (line.indent() != 0 || line.leadingTab() || !line.content().startsWith("%")) {
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
            return parseNodeAt(line, parentIndent, depth);
        }
        if (allowCompactSequence && line.indent() == parentIndent && isSequenceEntry(line.content())) {
            return parseSequence(parentIndent, depth + 1);
        }
        return emptyNode();
    }

    /**
     * Parses the node that starts on {@code line}.
     *
     * @param parentIndent indentation of the enclosing collection; continuation lines of a
     *                     multi-line scalar must be indented further than this
     */
    private YamlNode parseNodeAt(Line line, int parentIndent, int depth) {
        String content = line.content();
        boolean sequence = isSequenceEntry(content);
        boolean mapping = !sequence && findKeySeparator(content) >= 0;
        if ((sequence || mapping) && line.leadingTab()) {
            throw tabIndentation(line);
        }
        if (sequence) {
            return parseSequence(line.indent(), depth + 1);
        }
        if (mapping) {
            return parseMapping(line.indent(), depth + 1);
        }
        index++;
        return parseInlineValue(content, line.trailing(), line.lineNumber(), parentIndent, false);
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
            if (line.leadingTab()) {
                throw tabIndentation(line);
            }
            String content = line.content();
            if (!isSequenceEntry(content)) {
                break;
            }
            int offset = 1;
            boolean tabSeparated = false;
            while (offset < content.length() && isBlank(content.charAt(offset))) {
                tabSeparated |= content.charAt(offset) == '\t';
                offset++;
            }
            String rest = content.substring(offset);
            if (rest.isEmpty() || rest.startsWith("#")) {
                index++;
                sequence.add(parseBlockValue(indent, depth, false));
            } else {
                // Re-read the entry content as if it started its own line at the column after "- ".
                // A tab in the separator cannot serve as indentation of a nested block collection.
                Line item = new Line(line.lineNumber(), indent + offset, rest, tabSeparated, false, line.trailing());
                lines.set(index, item);
                sequence.add(parseNodeAt(item, indent, depth));
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
            if (line.leadingTab()) {
                throw tabIndentation(line);
            }
            String content = line.content();
            if (isSequenceEntry(content)) {
                throw new YamlParseException("Sequence entry is not allowed inside a mapping at line " + line.lineNumber());
            }
            if (isIndicatorWithBlank(content, '?')) {
                throw new YamlParseException("Complex mapping keys are not supported at line " + line.lineNumber());
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
                : parseInlineValue(rawValue, line.trailing(), line.lineNumber(), indent, false);
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
        if (!(parseInlineValue(key, "", lineNumber, -1, true) instanceof YamlScalar scalar)) {
            throw new YamlParseException("Only scalar mapping keys are supported at line " + lineNumber);
        }
        return scalar;
    }

    /**
     * Parses a node written on the current line. The line itself has already been consumed;
     * multi-line scalars continue on the following lines, which must be indented further than
     * {@code parentIndent}. Implicit keys are always single-line.
     */
    private YamlNode parseInlineValue(String raw, String trailing, int lineNumber, int parentIndent, boolean key) {
        char first = raw.charAt(0);
        if (first == '"' || first == '\'') {
            return parseQuoted(raw, trailing, lineNumber, parentIndent, key);
        }
        int commentStart = findCommentStart(raw);
        String text = trim(commentStart < 0 ? raw : raw.substring(0, commentStart));
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
        if (key) {
            return YamlScalar.plain(text);
        }
        if (hasMappingIndicator(text)) {
            throw new YamlParseException("Mapping values are not allowed here at line " + lineNumber + ", quote the value");
        }
        // A comment ends a plain scalar, so it cannot continue on the next line.
        return YamlScalar.plain(commentStart < 0 ? readPlainContinuation(text, parentIndent) : text);
    }

    /**
     * Folds the continuation lines of a multi-line plain scalar (YAML 1.2.2, 7.3.3 and 6.5):
     * a single line break becomes a space, and each empty line becomes a line feed.
     */
    private String readPlainContinuation(String firstLine, int parentIndent) {
        StringBuilder value = null;
        int emptyLines = 0;
        int resume = index;
        while (index < lines.size()) {
            Line line = current();
            String content = line.content();
            if (content.isEmpty()) {
                emptyLines++;
                index++;
                continue;
            }
            if (content.startsWith("#") || isDocumentMarker(line) || line.indent() <= parentIndent) {
                break;
            }
            int commentStart = findCommentStart(content);
            String part = trim(commentStart < 0 ? content : content.substring(0, commentStart));
            if (hasMappingIndicator(part)) {
                throw new YamlParseException("Mapping values are not allowed here at line " + line.lineNumber() + ", quote the value");
            }
            if (value == null) {
                value = new StringBuilder(firstLine);
            }
            value.append(emptyLines == 0 ? " " : "\n".repeat(emptyLines)).append(part);
            emptyLines = 0;
            index++;
            resume = index;
            if (commentStart >= 0) {
                break;
            }
        }
        // Empty lines after the last continuation line are not part of the scalar.
        index = resume;
        return value == null ? firstLine : value.toString();
    }

    private YamlScalar parseQuoted(String raw, String trailing, int lineNumber, int parentIndent, boolean key) {
        boolean doubleQuoted = raw.charAt(0) == '"';
        YamlScalarStyle style = doubleQuoted ? YamlScalarStyle.DOUBLE_QUOTED : YamlScalarStyle.SINGLE_QUOTED;
        int end = findQuoteEnd(raw, 1, doubleQuoted);
        if (end >= 0) {
            ensureOnlyComment(raw.substring(end + 1), lineNumber);
            String body = raw.substring(1, end);
            String value = doubleQuoted ? unescape(body, lineNumber) : body.replace("''", "'");
            return new YamlScalar(value, null, null, style);
        }
        if (key) {
            throw unterminatedQuote(doubleQuoted, lineNumber);
        }
        List<Segment> segments = new ArrayList<>();
        segments.add(new Segment(raw.substring(1) + trailing, lineNumber));
        while (true) {
            if (index >= lines.size()) {
                throw unterminatedQuote(doubleQuoted, lineNumber);
            }
            Line line = current();
            if (isDocumentMarker(line)) {
                throw new YamlParseException("Document marker inside a quoted scalar at line " + line.lineNumber());
            }
            if (!line.content().isEmpty() && line.indent() <= parentIndent) {
                throw new YamlParseException("Invalid indentation of a quoted scalar continuation at line " + line.lineNumber());
            }
            index++;
            String segment = line.content() + line.trailing();
            end = findQuoteEnd(segment, 0, doubleQuoted);
            if (end >= 0) {
                ensureOnlyComment(segment.substring(end + 1), line.lineNumber());
                segments.add(new Segment(segment.substring(0, end), line.lineNumber()));
                return new YamlScalar(foldQuoted(segments, doubleQuoted), null, null, style);
            }
            segments.add(new Segment(segment, line.lineNumber()));
        }
    }

    /**
     * Folds the lines of a multi-line quoted scalar (YAML 1.2.2, 7.3.1, 7.3.2 and 6.5). Leading
     * white space of continuation lines is already removed by the tokenizer; trailing white space
     * before a line break is removed unless the break is escaped.
     */
    private String foldQuoted(List<Segment> segments, boolean doubleQuoted) {
        StringBuilder result = new StringBuilder();
        int last = segments.size() - 1;
        boolean escapedBreak = false;
        int emptyLines = 0;
        for (int i = 0; i <= last; i++) {
            Segment segment = segments.get(i);
            String text = segment.text();
            if (i > 0 && i < last && trim(text).isEmpty()) {
                emptyLines++;
                continue;
            }
            if (i > 0) {
                if (emptyLines > 0) {
                    result.append("\n".repeat(emptyLines));
                } else if (!escapedBreak) {
                    result.append(' ');
                }
                emptyLines = 0;
            }
            if (i < last) {
                escapedBreak = doubleQuoted && endsWithEscapedLineBreak(text);
                text = escapedBreak ? text.substring(0, text.length() - 1) : trimTrailingUnescaped(text, doubleQuoted);
            }
            result.append(doubleQuoted ? unescape(text, segment.lineNumber()) : text.replace("''", "'"));
        }
        return result.toString();
    }

    private static boolean endsWithEscapedLineBreak(String text) {
        return !text.isEmpty() && text.charAt(text.length() - 1) == '\\' && !isEscaped(text, text.length() - 1);
    }

    private static String trimTrailingUnescaped(String text, boolean doubleQuoted) {
        int end = text.length();
        while (end > 0 && isBlank(text.charAt(end - 1)) && !(doubleQuoted && isEscaped(text, end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    /**
     * Whether the character at {@code position} is preceded by an odd number of backslashes.
     */
    private static boolean isEscaped(String text, int position) {
        int backslashes = 0;
        for (int i = position - 1; i >= 0 && text.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return (backslashes & 1) == 1;
    }

    private static void ensureOnlyComment(String trailing, int lineNumber) {
        String rest = trim(trailing);
        if (!rest.isEmpty() && !(isBlank(trailing.charAt(0)) && rest.startsWith("#"))) {
            throw new YamlParseException("Unexpected content after quoted scalar at line " + lineNumber);
        }
    }

    private static YamlParseException unterminatedQuote(boolean doubleQuoted, int lineNumber) {
        return new YamlParseException("Unterminated " + (doubleQuoted ? "double" : "single") + "-quoted scalar at line " + lineNumber);
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
        while (index < lines.size() && current().ignorable()) {
            index++;
        }
    }

    /**
     * A byte order mark may only appear at the start of a document (YAML 1.2.2, 5.2 and 9.1.1).
     */
    private void allowByteOrderMark() {
        if (index < lines.size() && lines.get(index).bom()) {
            lines.set(index, lines.get(index).withoutByteOrderMark());
        }
    }

    private Line current() {
        Line line = lines.get(index);
        if (line.bom()) {
            throw new YamlParseException("Byte order mark is only allowed at the start of a document (line " + line.lineNumber() + ")");
        }
        return line;
    }

    private void ensureDepth(int depth) {
        if (depth > limits.maxDepth()) {
            throw new YamlParseException("YAML nesting depth exceeds configured limit");
        }
    }

    private void ensureNothingAfterMarker(Line line) {
        if (!stripComment(line.content().substring(3)).isEmpty()) {
            throw new YamlParseException("Content on a document end marker line is not allowed at line " + line.lineNumber());
        }
    }

    private static YamlParseException invalidIndentation(Line line) {
        return new YamlParseException("Invalid indentation at line " + line.lineNumber());
    }

    private static YamlParseException tabIndentation(Line line) {
        return new YamlParseException("Tabs are not supported for indentation at line " + line.lineNumber());
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
            && !line.leadingTab()
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
            int end = findQuoteEnd(content, 1, first == '"');
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

    /**
     * Finds the closing quote, scanning from {@code from}; returns -1 when the scalar continues on the next line.
     */
    private static int findQuoteEnd(String text, int from, boolean doubleQuoted) {
        for (int i = from; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (doubleQuoted) {
                if (ch == '\\') {
                    i++;
                } else if (ch == '"') {
                    return i;
                }
            } else if (ch == '\'') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Position of a comment ({@code #} at the start or after white space), or -1.
     */
    private static int findCommentStart(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '#' && (i == 0 || isBlank(text.charAt(i - 1)))) {
                return i;
            }
        }
        return -1;
    }

    private static String stripComment(String text) {
        int commentStart = findCommentStart(text);
        return trim(commentStart < 0 ? text : text.substring(0, commentStart));
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
        List<Line> result = new ArrayList<>();
        int lineNumber = 1;
        int lineStart = 0;
        for (int i = 0; i <= text.length(); i++) {
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
        boolean bom = raw.startsWith("﻿");
        String line = bom ? raw.substring(1) : raw;
        ensurePrintable(line, lineNumber);
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        // White space after the indentation separates content but is not indentation itself.
        int start = indent;
        boolean leadingTab = false;
        while (start < line.length() && isBlank(line.charAt(start))) {
            leadingTab |= line.charAt(start) == '\t';
            start++;
        }
        int end = line.length();
        while (end > start && isBlank(line.charAt(end - 1))) {
            end--;
        }
        String content = line.substring(start, end);
        return new Line(lineNumber, indent, content, leadingTab && !content.isEmpty(), bom, line.substring(end));
    }

    /**
     * Rejects characters outside the YAML printable set (YAML 1.2.2, 5.1); line breaks are handled by the tokenizer.
     */
    private static void ensurePrintable(String line, int lineNumber) {
        for (int i = 0; i < line.length(); ) {
            int codePoint = line.codePointAt(i);
            if (codePoint == 0xFEFF) {
                throw new YamlParseException("Byte order mark is only allowed at the start of a document (line " + lineNumber + ")");
            }
            boolean printable = codePoint == '\t'
                || codePoint >= 0x20 && codePoint <= 0x7E
                || codePoint == 0x85
                || codePoint >= 0xA0 && codePoint <= 0xD7FF
                || codePoint >= 0xE000 && codePoint <= 0xFFFD
                || codePoint >= 0x10000 && codePoint <= 0x10FFFF;
            if (!printable) {
                throw new YamlParseException(String.format("Non-printable character U+%04X at line %d", codePoint, lineNumber));
            }
            i += Character.charCount(codePoint);
        }
    }

    /**
     * One physical line.
     *
     * @param indent     number of leading spaces
     * @param content    text after the indentation and any separating white space, without trailing white space
     * @param leadingTab whether a tab separates the indentation from non-empty content
     * @param bom        whether the line starts with a byte order mark
     * @param trailing   trailing white space removed from the content (significant in quoted scalars)
     */
    private record Line(int lineNumber, int indent, String content, boolean leadingTab, boolean bom, String trailing) {
        boolean ignorable() {
            return content.isEmpty() || content.startsWith("#");
        }

        Line withoutByteOrderMark() {
            return new Line(lineNumber, indent, content, leadingTab, false, trailing);
        }
    }

    private record Segment(String text, int lineNumber) {
    }
}
