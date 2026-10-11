package io.pure.yaml.core.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    private static final String FLOW_INDICATORS = ",[]{}";

    private final String text;
    private final YamlProcessingLimits limits;
    private List<Line> lines;
    private int index;
    private int aliasCount;
    private Map<String, YamlNode> anchors;
    private Set<YamlNode> openNodes;

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
            return new YamlDocument(emptyNode(null));
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
        // Anchors are scoped to a document (YAML 1.2.2, 9.2; RFC 9512, 3.2).
        anchors = new HashMap<>();
        openNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        String versionDirective = parseDirectives();
        skipIgnorable();
        YamlNode root;
        if (index < lines.size() && isDocumentStart(current())) {
            root = parseExplicitDocumentRoot(current());
        } else if (versionDirective != null) {
            throw new YamlParseException("Directives must be followed by a document start marker '---'");
        } else {
            root = parseBlockValue(-1, 0, false, null);
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
            return parseBlockValue(-1, 0, false, null);
        }
        // "--- value": the root node starts on the marker line; block collections cannot start there.
        if (isSequenceEntry(rest) || findKeySeparator(rest) >= 0) {
            throw new YamlParseException("Block collections cannot start on the document start line at line " + marker.lineNumber());
        }
        Line root = new Line(marker.lineNumber(), offset, rest, false, false, marker.trailing(), marker.raw());
        lines.set(index, root);
        return parseNodeAt(root, -1, 0, null, false);
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

    /**
     * Parses a node that starts on a following line, or returns an empty node.
     *
     * @param anchor anchor given on a preceding line, or {@code null}
     */
    private YamlNode parseBlockValue(int parentIndent, int depth, boolean allowCompactSequence, String anchor) {
        skipIgnorable();
        if (index >= lines.size()) {
            return emptyNode(anchor);
        }
        Line line = current();
        if (isDocumentMarker(line)) {
            return emptyNode(anchor);
        }
        if (line.indent() > parentIndent) {
            return parseNodeAt(line, parentIndent, depth, anchor, allowCompactSequence);
        }
        if (allowCompactSequence && line.indent() == parentIndent && isSequenceEntry(line.content())) {
            return parseSequence(parentIndent, depth + 1, anchor);
        }
        return emptyNode(anchor);
    }

    /**
     * Parses the node that starts on {@code line}.
     *
     * @param parentIndent         indentation of the enclosing collection; continuation lines of a
     *                             multi-line node must be indented further than this
     * @param allowCompactSequence whether a node given only by properties on this line may be a
     *                             sequence at {@code parentIndent} (the value of a block mapping)
     */
    private YamlNode parseNodeAt(Line line, int parentIndent, int depth, String anchor, boolean allowCompactSequence) {
        String content = line.content();
        boolean sequence = isSequenceEntry(content);
        boolean mapping = !sequence && findKeySeparator(content) >= 0;
        if ((sequence || mapping) && line.leadingTab()) {
            throw tabIndentation(line);
        }
        if (sequence) {
            return parseSequence(line.indent(), depth + 1, anchor);
        }
        if (mapping) {
            return parseMapping(line.indent(), depth + 1, anchor);
        }
        index++;
        Context context = allowCompactSequence ? Context.VALUE : Context.NODE;
        return parseInlineValue(content, line.trailing(), line.lineNumber(), parentIndent, depth, context, anchor);
    }

    private YamlSequence parseSequence(int indent, int depth, String anchor) {
        ensureDepth(depth);
        YamlSequence sequence = new YamlSequence(null, anchor);
        open(anchor, sequence);
        try {
            while (true) {
                skipIgnorable();
                if (index >= lines.size()) {
                    return sequence;
                }
                Line line = current();
                if (line.indent() < indent || isDocumentMarker(line)) {
                    return sequence;
                }
                if (line.indent() > indent) {
                    throw invalidIndentation(line);
                }
                if (line.leadingTab()) {
                    throw tabIndentation(line);
                }
                String content = line.content();
                if (!isSequenceEntry(content)) {
                    return sequence;
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
                    sequence.add(parseBlockValue(indent, depth, false, null));
                } else {
                    // Re-read the entry content as if it started its own line at the column after "- ".
                    // A tab in the separator cannot serve as indentation of a nested block collection.
                    Line item = new Line(line.lineNumber(), indent + offset, rest, tabSeparated, false, line.trailing(), line.raw());
                    lines.set(index, item);
                    sequence.add(parseNodeAt(item, indent, depth, null, false));
                }
            }
        } finally {
            close(sequence);
        }
    }

    private YamlMapping parseMapping(int indent, int depth, String anchor) {
        ensureDepth(depth);
        YamlMapping mapping = new YamlMapping(null, anchor);
        open(anchor, mapping);
        try {
            while (true) {
                skipIgnorable();
                if (index >= lines.size()) {
                    return mapping;
                }
                Line line = current();
                if (line.indent() < indent || isDocumentMarker(line)) {
                    return mapping;
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
                YamlNode key = parseKey(content.substring(0, separator), line.lineNumber(), depth);
                if (mapping.containsKey(keyText(key))) {
                    throw new YamlParseException("Duplicate mapping key '" + keyText(key) + "' at line " + line.lineNumber());
                }
                String rawValue = trim(content.substring(separator + 1));
                YamlNode value = rawValue.isEmpty() || rawValue.startsWith("#")
                    ? parseBlockValue(indent, depth, true, null)
                    : parseInlineValue(rawValue, line.trailing(), line.lineNumber(), indent, depth, Context.VALUE, null);
                mapping.put(key, value);
            }
        } finally {
            close(mapping);
        }
    }

    private YamlNode parseKey(String rawKey, int lineNumber, int depth) {
        String key = trim(rawKey);
        if (key.isEmpty()) {
            throw new YamlParseException("Empty mapping keys are not supported at line " + lineNumber);
        }
        if (isIndicatorWithBlank(key, '?')) {
            throw new YamlParseException("Complex mapping keys are not supported at line " + lineNumber);
        }
        return checkScalarKey(parseInlineValue(key, "", lineNumber, -1, depth, Context.KEY, null), lineNumber);
    }

    /**
     * Parses a node written on the current line. The line itself has already been consumed;
     * multi-line nodes continue on the following lines, which must be indented further than
     * {@code parentIndent}. Implicit keys are always single-line.
     *
     * @param outerAnchor anchor given on a preceding line, or {@code null}
     */
    private YamlNode parseInlineValue(
        String raw,
        String trailing,
        int lineNumber,
        int parentIndent,
        int depth,
        Context context,
        String outerAnchor
    ) {
        Properties properties = readProperties(raw, 0, lineNumber, false);
        if (properties.anchor() != null && outerAnchor != null) {
            throw new YamlParseException("A node cannot have two anchors at line " + lineNumber);
        }
        String anchor = properties.anchor() != null ? properties.anchor() : outerAnchor;
        String text = raw.substring(properties.end());
        if (text.isEmpty() || text.startsWith("#")) {
            if (context == Context.KEY) {
                throw new YamlParseException("Empty mapping keys are not supported at line " + lineNumber);
            }
            // Only properties on this line: the node itself starts on the next line.
            return parseBlockValue(parentIndent, depth, context == Context.VALUE, anchor);
        }
        char first = text.charAt(0);
        switch (first) {
            case '"', '\'' -> {
                Quoted quoted = readQuoted(text + trailing, 0, lineNumber, parentIndent, context != Context.KEY);
                ensureOnlyComment(quoted.lineText().substring(quoted.end()), quoted.lineNumber());
                return register(new YamlScalar(quoted.value(), null, anchor, quoted.style()));
            }
            case '*' -> {
                if (anchor != null) {
                    throw new YamlParseException("An alias cannot have an anchor at line " + lineNumber);
                }
                return parseAlias(stripComment(text), lineNumber);
            }
            case '[', '{' -> {
                if (context == Context.KEY) {
                    throw new YamlParseException("Only scalar mapping keys are supported at line " + lineNumber);
                }
                FlowParser flow = new FlowParser(text + trailing, lineNumber, parentIndent);
                YamlNode node = flow.parseCollection(depth, anchor);
                ensureOnlyComment(flow.remainder(), flow.lineNumber());
                return node;
            }
            case '|', '>' -> {
                if (context == Context.KEY) {
                    throw new YamlParseException("Block scalars cannot be mapping keys at line " + lineNumber);
                }
                return parseBlockScalar(text, lineNumber, parentIndent, anchor);
            }
            case ']', '}', ',', '%', '@', '`' ->
                throw new YamlParseException("Plain scalar cannot start with '" + first + "' at line " + lineNumber);
            default -> {
            }
        }
        int commentStart = findCommentStart(text);
        String plain = trim(commentStart < 0 ? text : text.substring(0, commentStart));
        if (isSequenceEntry(plain)) {
            throw new YamlParseException("Sequence entry is not allowed on a mapping value line at line " + lineNumber);
        }
        if (isIndicatorWithBlank(plain, '?')) {
            throw new YamlParseException("Complex mapping keys are not supported at line " + lineNumber);
        }
        if (context == Context.KEY) {
            return register(new YamlScalar(plain, null, anchor, YamlScalarStyle.PLAIN));
        }
        if (hasMappingIndicator(plain)) {
            throw new YamlParseException("Mapping values are not allowed here at line " + lineNumber + ", quote the value");
        }
        // A comment ends a plain scalar, so it cannot continue on the next line.
        String value = commentStart < 0 ? readPlainContinuation(plain, parentIndent) : plain;
        return register(new YamlScalar(value, null, anchor, YamlScalarStyle.PLAIN));
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

    /**
     * Reads a quoted scalar whose opening quote is at {@code start} in {@code firstText}. Positions are
     * offsets rather than substrings, so that long single-line flow collections are read in linear time.
     */
    private Quoted readQuoted(String firstText, int start, int lineNumber, int parentIndent, boolean multiLine) {
        boolean doubleQuoted = firstText.charAt(start) == '"';
        YamlScalarStyle style = doubleQuoted ? YamlScalarStyle.DOUBLE_QUOTED : YamlScalarStyle.SINGLE_QUOTED;
        int end = findQuoteEnd(firstText, start + 1, doubleQuoted);
        if (end >= 0) {
            String body = firstText.substring(start + 1, end);
            String value = doubleQuoted ? unescape(body, lineNumber) : body.replace("''", "'");
            return new Quoted(value, style, firstText, end + 1, lineNumber);
        }
        if (!multiLine) {
            throw unterminatedQuote(doubleQuoted, lineNumber);
        }
        List<Segment> segments = new ArrayList<>();
        segments.add(new Segment(firstText.substring(start + 1), lineNumber));
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
                segments.add(new Segment(segment.substring(0, end), line.lineNumber()));
                return new Quoted(foldQuoted(segments, doubleQuoted), style, segment, end + 1, line.lineNumber());
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

    /**
     * Reads a literal ({@code |}) or folded ({@code >}) block scalar (YAML 1.2.2, 8.1).
     */
    private YamlScalar parseBlockScalar(String header, int lineNumber, int parentIndent, String anchor) {
        boolean literal = header.charAt(0) == '|';
        int indentation = 0;
        char chomping = 0;
        int position = 1;
        while (position < header.length() && position <= 2) {
            char ch = header.charAt(position);
            if (ch >= '1' && ch <= '9' && indentation == 0) {
                indentation = ch - '0';
            } else if ((ch == '+' || ch == '-') && chomping == 0) {
                chomping = ch;
            } else {
                break;
            }
            position++;
        }
        String rest = header.substring(position);
        if (!rest.isEmpty() && !(isBlank(rest.charAt(0)) && (trim(rest).isEmpty() || trim(rest).startsWith("#")))) {
            throw new YamlParseException("Invalid block scalar header at line " + lineNumber + ": " + header);
        }

        int contentIndent = indentation > 0 ? Math.max(parentIndent, 0) + indentation : -1;
        int leadingEmptySpaces = 0;
        // Empty lines are null; the end of the input ends the last line like a line break does.
        List<String> entries = new ArrayList<>();
        while (index < lines.size()) {
            Line line = current();
            String raw = line.raw();
            int spaces = line.indent();
            boolean spacesOnly = spaces == raw.length();
            if (contentIndent < 0) {
                if (spacesOnly) {
                    leadingEmptySpaces = Math.max(leadingEmptySpaces, spaces);
                    entries.add(null);
                    index++;
                    continue;
                }
                if (spaces <= parentIndent || isDocumentMarker(line)) {
                    ensureNotTabIndented(raw, line.lineNumber());
                    break;
                }
                contentIndent = spaces;
                if (leadingEmptySpaces > contentIndent) {
                    throw new YamlParseException(
                        "Leading empty line of a block scalar is indented more than its content at line " + line.lineNumber()
                    );
                }
            }
            if (spacesOnly) {
                // Lines of spaces that are indented further than the content are content themselves.
                entries.add(spaces > contentIndent ? raw.substring(contentIndent) : null);
                index++;
                continue;
            }
            if (spaces < contentIndent || isDocumentMarker(line)) {
                ensureNotTabIndented(raw, line.lineNumber());
                break;
            }
            entries.add(raw.substring(contentIndent));
            index++;
        }

        int lastContent = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) != null) {
                lastContent = i;
            }
        }
        int trailingEmptyLines = entries.size() - lastContent - 1;
        String lastBreak = lastContent >= 0 ? "\n" : "";
        String body = literal ? literalBody(entries, lastContent) : foldedBody(entries, lastContent);
        String value = switch (chomping) {
            case '-' -> body;
            case '+' -> body + lastBreak + "\n".repeat(trailingEmptyLines);
            default -> body + lastBreak;
        };
        YamlScalarStyle style = literal ? YamlScalarStyle.LITERAL : YamlScalarStyle.FOLDED;
        return register(new YamlScalar(value, null, anchor, style));
    }

    private static void ensureNotTabIndented(String raw, int lineNumber) {
        if (!raw.isEmpty() && trim(raw).isEmpty()) {
            throw new YamlParseException("Tabs are not supported for indentation at line " + lineNumber);
        }
    }

    private static String literalBody(List<String> entries, int lastContent) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i <= lastContent; i++) {
            if (i > 0) {
                body.append('\n');
            }
            body.append(entries.get(i) == null ? "" : entries.get(i));
        }
        return body.toString();
    }

    /**
     * Folds lines that start with a non-space character; line breaks around more-indented
     * lines and empty lines are kept (YAML 1.2.2, 8.1.3).
     */
    private static String foldedBody(List<String> entries, int lastContent) {
        StringBuilder body = new StringBuilder();
        Boolean previousSpaced = null;
        int emptyLines = 0;
        for (int i = 0; i <= lastContent; i++) {
            String text = entries.get(i);
            if (text == null) {
                emptyLines++;
                continue;
            }
            boolean spaced = isBlank(text.charAt(0));
            if (previousSpaced == null) {
                body.append("\n".repeat(emptyLines));
            } else if (!previousSpaced && !spaced) {
                body.append(emptyLines == 0 ? " " : "\n".repeat(emptyLines));
            } else {
                body.append("\n".repeat(emptyLines + 1));
            }
            body.append(text);
            emptyLines = 0;
            previousSpaced = spaced;
        }
        return body.toString();
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
            throw new YamlParseException("Unexpected content after the node at line " + lineNumber);
        }
    }

    private static YamlParseException unterminatedQuote(boolean doubleQuoted, int lineNumber) {
        return new YamlParseException("Unterminated " + (doubleQuoted ? "double" : "single") + "-quoted scalar at line " + lineNumber);
    }

    /**
     * Reads node properties at the start of {@code text}: an anchor ({@code &name}); tags are not supported.
     */
    private static Properties readProperties(String text, int from, int lineNumber, boolean flow) {
        String anchor = null;
        int position = from;
        while (position < text.length()) {
            char ch = text.charAt(position);
            if (ch == '!') {
                throw new YamlParseException("Tags are not supported at line " + lineNumber);
            }
            if (ch != '&') {
                break;
            }
            if (anchor != null) {
                throw new YamlParseException("A node cannot have two anchors at line " + lineNumber);
            }
            int start = ++position;
            while (position < text.length() && isAnchorChar(text.charAt(position))) {
                position++;
            }
            anchor = text.substring(start, position);
            if (anchor.isEmpty()) {
                throw new YamlParseException("Anchor name expected at line " + lineNumber);
            }
            if (position < text.length() && !isBlank(text.charAt(position)) && !flow) {
                throw new YamlParseException("Anchor must be followed by white space at line " + lineNumber);
            }
            while (position < text.length() && isBlank(text.charAt(position))) {
                position++;
            }
        }
        return new Properties(anchor, position);
    }

    private YamlAlias parseAlias(String text, int lineNumber) {
        String name = text.substring(1);
        if (name.isEmpty() || !name.chars().allMatch(ch -> isAnchorChar((char) ch))) {
            throw new YamlParseException("Invalid alias at line " + lineNumber + ": " + text);
        }
        return resolveAlias(name, lineNumber);
    }

    private YamlAlias resolveAlias(String name, int lineNumber) {
        aliasCount++;
        if (aliasCount > limits.maxAliases()) {
            throw new YamlParseException("Alias limit exceeded at line " + lineNumber);
        }
        YamlNode target = anchors.get(name);
        if (target == null) {
            throw new YamlParseException("Undefined alias '*" + name + "' at line " + lineNumber);
        }
        if (openNodes.contains(target)) {
            throw new YamlParseException("Recursive alias '*" + name + "' at line " + lineNumber);
        }
        return new YamlAlias(name, target);
    }

    private static YamlNode checkScalarKey(YamlNode key, int lineNumber) {
        YamlNode scalar = key instanceof YamlAlias alias ? alias.target() : key;
        if (!(scalar instanceof YamlScalar value) || value.value() == null) {
            throw new YamlParseException("Only scalar mapping keys are supported at line " + lineNumber);
        }
        return key;
    }

    private static String keyText(YamlNode key) {
        YamlNode scalar = key instanceof YamlAlias alias ? alias.target() : key;
        return ((YamlScalar) scalar).value();
    }

    private void open(String anchor, YamlNode node) {
        if (anchor != null) {
            anchors.put(anchor, node);
            openNodes.add(node);
        }
    }

    private void close(YamlNode node) {
        openNodes.remove(node);
    }

    private <T extends YamlNode> T register(T node) {
        if (node.anchor() != null) {
            anchors.put(node.anchor(), node);
        }
        return node;
    }

    private YamlScalar emptyNode(String anchor) {
        return register(new YamlScalar(null, null, anchor, YamlScalarStyle.PLAIN));
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

    /**
     * Finds the {@code :} that separates an implicit key on this line from its value, or -1.
     * Node properties before the key are skipped; quoted, alias and flow keys end where their syntax ends.
     */
    private static int findKeySeparator(String content) {
        int position = 0;
        while (position < content.length() && (content.charAt(position) == '&' || content.charAt(position) == '!')) {
            while (position < content.length() && !isBlank(content.charAt(position))) {
                position++;
            }
            while (position < content.length() && isBlank(content.charAt(position))) {
                position++;
            }
        }
        if (position >= content.length()) {
            return -1;
        }
        char first = content.charAt(position);
        int keyEnd;
        if (first == '"' || first == '\'') {
            int close = findQuoteEnd(content, position + 1, first == '"');
            if (close < 0) {
                return -1;
            }
            keyEnd = close + 1;
        } else if (first == '*') {
            keyEnd = position + 1;
            while (keyEnd < content.length() && isAnchorChar(content.charAt(keyEnd))) {
                keyEnd++;
            }
        } else if (first == '[' || first == '{') {
            int close = findFlowEnd(content, position);
            if (close < 0) {
                return -1;
            }
            keyEnd = close + 1;
        } else {
            for (int i = position; i < content.length(); i++) {
                if (content.charAt(i) == '#' && i > 0 && isBlank(content.charAt(i - 1))) {
                    return -1;
                }
                if (isMappingIndicatorAt(content, i)) {
                    return i;
                }
            }
            return -1;
        }
        while (keyEnd < content.length() && isBlank(content.charAt(keyEnd))) {
            keyEnd++;
        }
        return isMappingIndicatorAt(content, keyEnd) ? keyEnd : -1;
    }

    /**
     * Finds the bracket that closes the flow collection starting at {@code start} on the same line, or -1.
     */
    private static int findFlowEnd(String content, int start) {
        int depth = 0;
        char previous = ' ';
        for (int i = start; i < content.length(); i++) {
            char ch = content.charAt(i);
            if ((ch == '"' || ch == '\'') && "[{,:".indexOf(previous) >= 0) {
                int close = findQuoteEnd(content, i + 1, ch == '"');
                if (close < 0) {
                    return -1;
                }
                i = close;
            } else if (ch == '[' || ch == '{') {
                depth++;
            } else if (ch == ']' || ch == '}') {
                if (--depth == 0) {
                    return i;
                }
            } else if (ch == '#' && isBlank(content.charAt(i - 1))) {
                return -1;
            }
            if (!isBlank(ch)) {
                previous = ch;
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

    private static boolean isAnchorChar(char ch) {
        return !isBlank(ch) && FLOW_INDICATORS.indexOf(ch) < 0;
    }

    private static List<Line> tokenize(String text) {
        List<Line> result = new ArrayList<>();
        int lineNumber = 1;
        int lineStart = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n' || text.charAt(i) == '\r') {
                result.add(parseLine(text.substring(lineStart, i), lineNumber++));
                if (text.charAt(i) == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                lineStart = i + 1;
            }
        }
        // A final line break ends the last line; it does not start an empty one.
        if (lineStart < text.length()) {
            result.add(parseLine(text.substring(lineStart), lineNumber));
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
        return new Line(lineNumber, indent, content, leadingTab && !content.isEmpty(), bom, line.substring(end), line);
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
     * Reads a flow collection ({@code [...]} or {@code {...}}), which may span several lines
     * (YAML 1.2.2, 7.4 and 7.5).
     */
    private final class FlowParser {
        private final int parentIndent;
        private final int startLine;
        private String text;
        private int position;
        private int lineNumber;

        FlowParser(String text, int lineNumber, int parentIndent) {
            this.text = text;
            this.lineNumber = lineNumber;
            this.startLine = lineNumber;
            this.parentIndent = parentIndent;
        }

        String remainder() {
            return text.substring(position);
        }

        int lineNumber() {
            return lineNumber;
        }

        YamlNode parseCollection(int depth, String anchor) {
            return text.charAt(position) == '[' ? parseFlowSequence(depth + 1, anchor) : parseFlowMapping(depth + 1, anchor);
        }

        private YamlSequence parseFlowSequence(int depth, String anchor) {
            ensureDepth(depth);
            YamlSequence sequence = new YamlSequence(null, anchor);
            open(anchor, sequence);
            try {
                position++;
                while (true) {
                    skipSeparation();
                    if (peek() == ']') {
                        position++;
                        return sequence;
                    }
                    if (isIndicator('?')) {
                        throw new YamlParseException("Complex mapping keys are not supported at line " + lineNumber);
                    }
                    if (isValueIndicator()) {
                        throw new YamlParseException("Empty mapping keys are not supported at line " + lineNumber);
                    }
                    int entryLine = lineNumber;
                    YamlNode entry = parseFlowNode(depth);
                    boolean crossedLine = skipSeparation();
                    if (isPairSeparator(entry)) {
                        // A single-pair mapping such as [key: value]; its key must be on one line.
                        if (crossedLine || lineNumber != entryLine) {
                            throw new YamlParseException("Implicit keys must be on a single line at line " + lineNumber);
                        }
                        position++;
                        ensureDepth(depth + 1);
                        YamlMapping pair = new YamlMapping();
                        YamlNode key = checkScalarKey(entry, lineNumber);
                        skipSeparation();
                        YamlNode value = peek() == ',' || peek() == ']' ? emptyNode(null) : parseFlowNode(depth + 1);
                        pair.put(key, value);
                        entry = pair;
                        skipSeparation();
                    }
                    sequence.add(entry);
                    if (!nextEntry(']')) {
                        return sequence;
                    }
                }
            } finally {
                close(sequence);
            }
        }

        private YamlMapping parseFlowMapping(int depth, String anchor) {
            ensureDepth(depth);
            YamlMapping mapping = new YamlMapping(null, anchor);
            open(anchor, mapping);
            try {
                position++;
                while (true) {
                    skipSeparation();
                    if (peek() == '}') {
                        position++;
                        return mapping;
                    }
                    if (isIndicator('?')) {
                        throw new YamlParseException("Complex mapping keys are not supported at line " + lineNumber);
                    }
                    if (isValueIndicator()) {
                        throw new YamlParseException("Empty mapping keys are not supported at line " + lineNumber);
                    }
                    int keyLine = lineNumber;
                    YamlNode key = checkScalarKey(parseFlowNode(depth), keyLine);
                    skipSeparation();
                    YamlNode value;
                    if (isPairSeparator(key)) {
                        position++;
                        skipSeparation();
                        value = peek() == ',' || peek() == '}' ? emptyNode(null) : parseFlowNode(depth);
                        skipSeparation();
                    } else {
                        value = emptyNode(null);
                    }
                    if (mapping.containsKey(keyText(key))) {
                        throw new YamlParseException("Duplicate mapping key '" + keyText(key) + "' at line " + keyLine);
                    }
                    mapping.put(key, value);
                    if (!nextEntry('}')) {
                        return mapping;
                    }
                }
            } finally {
                close(mapping);
            }
        }

        /**
         * Consumes the separator after an entry.
         *
         * @return {@code true} when another entry may follow, {@code false} when the collection is closed
         */
        private boolean nextEntry(char close) {
            char ch = peek();
            if (ch == ',') {
                position++;
                return true;
            }
            if (ch == close) {
                position++;
                return false;
            }
            throw new YamlParseException("Expected ',' or '" + close + "' in flow collection at line " + lineNumber);
        }

        private YamlNode parseFlowNode(int depth) {
            Properties properties = readProperties(text, position, lineNumber, true);
            position = properties.end();
            String anchor = properties.anchor();
            if (anchor != null) {
                skipSeparation();
            }
            char ch = peek();
            if (ch == ',' || ch == ']' || ch == '}' || isValueIndicator()) {
                if (anchor == null) {
                    throw new YamlParseException("Missing flow collection entry at line " + lineNumber);
                }
                return emptyNode(anchor);
            }
            switch (ch) {
                case '[' -> {
                    return parseFlowSequence(depth + 1, anchor);
                }
                case '{' -> {
                    return parseFlowMapping(depth + 1, anchor);
                }
                case '"', '\'' -> {
                    Quoted quoted = readQuoted(text, position, lineNumber, parentIndent, true);
                    if (quoted.lineNumber() == lineNumber) {
                        position = quoted.end();
                    } else {
                        text = quoted.lineText();
                        position = quoted.end();
                        lineNumber = quoted.lineNumber();
                    }
                    return register(new YamlScalar(quoted.value(), null, anchor, quoted.style()));
                }
                case '*' -> {
                    if (anchor != null) {
                        throw new YamlParseException("An alias cannot have an anchor at line " + lineNumber);
                    }
                    int start = ++position;
                    while (position < text.length() && isAnchorChar(text.charAt(position))) {
                        position++;
                    }
                    if (position == start) {
                        throw new YamlParseException("Alias name expected at line " + lineNumber);
                    }
                    return resolveAlias(text.substring(start, position), lineNumber);
                }
                case '|', '>' -> throw new YamlParseException("Block scalars are not allowed inside flow collections at line " + lineNumber);
                case '#', '%', '@', '`' ->
                    throw new YamlParseException("Plain scalar cannot start with '" + ch + "' at line " + lineNumber);
                case '-', '?', ':' -> {
                    if (!isPlainSafe(position + 1)) {
                        throw new YamlParseException("Unexpected '" + ch + "' in flow collection at line " + lineNumber);
                    }
                }
                default -> {
                }
            }
            return register(new YamlScalar(readFlowPlain(), null, anchor, YamlScalarStyle.PLAIN));
        }

        /**
         * Reads a plain scalar inside a flow collection; it may continue on following lines.
         */
        private String readFlowPlain() {
            StringBuilder value = new StringBuilder();
            while (true) {
                int start = position;
                boolean comment = false;
                while (position < text.length()) {
                    char ch = text.charAt(position);
                    if (FLOW_INDICATORS.indexOf(ch) >= 0 || isValueIndicator()) {
                        break;
                    }
                    if (ch == '#' && position > start && isBlank(text.charAt(position - 1))) {
                        comment = true;
                        break;
                    }
                    position++;
                }
                value.append(trim(text.substring(start, position)));
                if (comment || position < text.length()) {
                    return value.toString();
                }
                // The line ended inside the scalar: it continues if the next non-empty line starts with plain text.
                int emptyLines = 0;
                while (true) {
                    advanceLine();
                    if (!text.isEmpty()) {
                        break;
                    }
                    emptyLines++;
                }
                char next = text.charAt(0);
                if (next == '#' || FLOW_INDICATORS.indexOf(next) >= 0 || isValueIndicator()) {
                    return value.toString();
                }
                value.append(emptyLines == 0 ? " " : "\n".repeat(emptyLines));
            }
        }

        /**
         * Skips white space, comments and line breaks.
         *
         * @return whether a line break was crossed
         */
        private boolean skipSeparation() {
            boolean crossedLine = false;
            while (true) {
                while (position < text.length() && isBlank(text.charAt(position))) {
                    position++;
                }
                if (position < text.length() && text.charAt(position) == '#'
                    && (position == 0 || isBlank(text.charAt(position - 1)))) {
                    position = text.length();
                }
                if (position < text.length()) {
                    return crossedLine;
                }
                advanceLine();
                crossedLine = true;
            }
        }

        private void advanceLine() {
            if (index >= lines.size()) {
                throw new YamlParseException("Unterminated flow collection starting at line " + startLine);
            }
            Line line = current();
            if (isDocumentMarker(line)) {
                throw new YamlParseException("Document marker inside a flow collection at line " + line.lineNumber());
            }
            if (!line.ignorable() && line.indent() <= parentIndent) {
                throw new YamlParseException("Invalid indentation inside a flow collection at line " + line.lineNumber());
            }
            index++;
            text = line.content() + line.trailing();
            position = 0;
            lineNumber = line.lineNumber();
        }

        private char peek() {
            return text.charAt(position);
        }

        /**
         * Whether a {@code :} at the current position separates a key from its value. After a
         * JSON-like key (quoted scalar or flow collection) the value may follow without white space.
         */
        private boolean isPairSeparator(YamlNode key) {
            if (position >= text.length() || peek() != ':') {
                return false;
            }
            boolean jsonLike = key instanceof YamlSequence || key instanceof YamlMapping
                || key instanceof YamlScalar scalar && scalar.style() != YamlScalarStyle.PLAIN;
            return jsonLike || isValueIndicator();
        }

        private boolean isValueIndicator() {
            return isIndicator(':');
        }

        private boolean isIndicator(char indicator) {
            return position < text.length() && text.charAt(position) == indicator && !isPlainSafe(position + 1);
        }

        private boolean isPlainSafe(int at) {
            return at < text.length() && !isBlank(text.charAt(at)) && FLOW_INDICATORS.indexOf(text.charAt(at)) < 0;
        }
    }

    private enum Context {
        /** An implicit mapping key: single-line, no block nodes. */
        KEY,
        /** A block mapping value: may be followed by a compact sequence. */
        VALUE,
        /** Any other node. */
        NODE
    }

    /**
     * @param end position after the properties and the white space that follows them
     */
    private record Properties(String anchor, int end) {
    }

    private record Quoted(String value, YamlScalarStyle style, String lineText, int end, int lineNumber) {
    }

    /**
     * One physical line.
     *
     * @param indent     number of leading spaces
     * @param content    text after the indentation and any separating white space, without trailing white space
     * @param leadingTab whether a tab separates the indentation from non-empty content
     * @param bom        whether the line starts with a byte order mark
     * @param trailing   trailing white space removed from the content (significant in quoted scalars)
     * @param raw        the whole line without the byte order mark (used by block scalars)
     */
    private record Line(
        int lineNumber,
        int indent,
        String content,
        boolean leadingTab,
        boolean bom,
        String trailing,
        String raw
    ) {
        boolean ignorable() {
            return content.isEmpty() || content.startsWith("#");
        }

        Line withoutByteOrderMark() {
            return new Line(lineNumber, indent, content, leadingTab, false, trailing, raw);
        }
    }

    private record Segment(String text, int lineNumber) {
    }
}
