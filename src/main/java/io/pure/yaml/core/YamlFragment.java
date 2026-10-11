package io.pure.yaml.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.pure.yaml.core.model.YamlAlias;
import io.pure.yaml.core.model.YamlDocument;
import io.pure.yaml.core.model.YamlEntry;
import io.pure.yaml.core.model.YamlMapping;
import io.pure.yaml.core.model.YamlNode;
import io.pure.yaml.core.model.YamlScalar;
import io.pure.yaml.core.model.YamlSequence;
import io.pure.yaml.core.model.YamlStream;

/**
 * Evaluates fragment identifiers of the {@code application/yaml} media type (RFC 9512, 1.2):
 * <ul>
 *     <li>{@code *name} selects the first node in the stream with anchor {@code name};</li>
 *     <li>an empty fragment or one starting with {@code /} is a JSON Pointer (RFC 6901), evaluated
 *     on the representation graph of a single-document stream, following aliases.</li>
 * </ul>
 * A fragment that matches no node yields an empty result. A leading {@code #} is ignored.
 */
public final class YamlFragment {
    private static final int MAX_ALIAS_CHAIN = 64;

    private YamlFragment() {
    }

    public static Optional<YamlNode> resolve(YamlStream stream, String fragment) {
        Objects.requireNonNull(stream, "stream");
        String identifier = stripHash(fragment);
        if (identifier.startsWith("*")) {
            String anchor = identifier.substring(1);
            for (YamlDocument document : stream.documents()) {
                Optional<YamlNode> node = findAnchor(document.root(), anchor);
                if (node.isPresent()) {
                    return node;
                }
            }
            return Optional.empty();
        }
        List<YamlDocument> documents = stream.documents();
        if (documents.size() != 1) {
            throw new IllegalArgumentException("JSON Pointer fragments are defined only for single-document streams");
        }
        return resolve(documents.getFirst().root(), identifier);
    }

    /**
     * Evaluates a fragment against the root node of a single document.
     */
    public static Optional<YamlNode> resolve(YamlNode root, String fragment) {
        Objects.requireNonNull(root, "root");
        String identifier = stripHash(fragment);
        if (identifier.startsWith("*")) {
            return findAnchor(root, identifier.substring(1));
        }
        if (!identifier.isEmpty() && !identifier.startsWith("/")) {
            throw new IllegalArgumentException("Unsupported YAML fragment identifier: " + fragment);
        }
        return evaluatePointer(root, percentDecode(identifier));
    }

    private static String stripHash(String fragment) {
        Objects.requireNonNull(fragment, "fragment");
        return fragment.startsWith("#") ? fragment.substring(1) : fragment;
    }

    /**
     * Finds the first node, in document order, that defines the anchor. Aliases are not followed,
     * so the search terminates on any graph.
     */
    private static Optional<YamlNode> findAnchor(YamlNode root, String anchor) {
        Deque<YamlNode> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            YamlNode node = pending.pop();
            if (anchor.equals(node.anchor())) {
                return Optional.of(node);
            }
            if (node instanceof YamlSequence sequence) {
                List<YamlNode> values = sequence.values();
                for (int i = values.size() - 1; i >= 0; i--) {
                    pending.push(values.get(i));
                }
            } else if (node instanceof YamlMapping mapping) {
                List<YamlEntry> entries = mapping.entries();
                for (int i = entries.size() - 1; i >= 0; i--) {
                    pending.push(entries.get(i).value());
                    pending.push(entries.get(i).key());
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<YamlNode> evaluatePointer(YamlNode root, String pointer) {
        YamlNode current = dereference(root);
        if (pointer.isEmpty()) {
            return Optional.ofNullable(current);
        }
        for (String rawToken : pointer.substring(1).split("/", -1)) {
            String token = unescapeToken(rawToken);
            if (current instanceof YamlMapping mapping) {
                current = findValue(mapping, token);
            } else if (current instanceof YamlSequence sequence && token.matches("0|[1-9][0-9]{0,9}")) {
                long position = Long.parseLong(token);
                current = position < sequence.size() ? sequence.get((int) position) : null;
            } else {
                current = null;
            }
            current = dereference(current);
            if (current == null) {
                return Optional.empty();
            }
        }
        return Optional.of(current);
    }

    private static YamlNode findValue(YamlMapping mapping, String key) {
        for (YamlEntry entry : mapping.entries()) {
            if (dereference(entry.key()) instanceof YamlScalar scalar && key.equals(scalar.value())) {
                return entry.value();
            }
        }
        return null;
    }

    /**
     * Follows aliases to the anchored node; returns {@code null} for unresolved aliases.
     */
    private static YamlNode dereference(YamlNode node) {
        YamlNode current = node;
        for (int i = 0; current instanceof YamlAlias alias; i++) {
            if (i == MAX_ALIAS_CHAIN) {
                throw new IllegalArgumentException("Alias chain is too long: *" + alias.alias());
            }
            current = alias.target();
        }
        return current;
    }

    private static String unescapeToken(String token) {
        StringBuilder result = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char ch = token.charAt(i);
            if (ch != '~') {
                result.append(ch);
                continue;
            }
            char next = i + 1 < token.length() ? token.charAt(++i) : ' ';
            if (next == '0') {
                result.append('~');
            } else if (next == '1') {
                result.append('/');
            } else {
                throw new IllegalArgumentException("Invalid JSON Pointer escape in token: " + token);
            }
        }
        return result.toString();
    }

    /**
     * Decodes the URI fragment representation of a JSON Pointer (RFC 6901, 6).
     */
    private static String percentDecode(String fragment) {
        if (fragment.indexOf('%') < 0) {
            return fragment;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < fragment.length(); ) {
            int codePoint = fragment.codePointAt(i);
            if (codePoint == '%') {
                if (i + 2 >= fragment.length() || Character.digit(fragment.charAt(i + 1), 16) < 0
                    || Character.digit(fragment.charAt(i + 2), 16) < 0) {
                    throw new IllegalArgumentException("Invalid percent-encoding in fragment: " + fragment);
                }
                bytes.write(Integer.parseInt(fragment.substring(i + 1, i + 3), 16));
                i += 3;
            } else {
                byte[] encoded = Character.toString(codePoint).getBytes(StandardCharsets.UTF_8);
                bytes.write(encoded, 0, encoded.length);
                i += Character.charCount(codePoint);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Fragment is not valid UTF-8 after percent-decoding: " + fragment, exception);
        }
    }
}
