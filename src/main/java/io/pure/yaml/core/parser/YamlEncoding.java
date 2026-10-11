package io.pure.yaml.core.parser;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Detects and decodes the character encoding of a YAML stream (YAML 1.2.2, 5.2): UTF-8, UTF-16 or
 * UTF-32, recognized by a byte order mark or by the null bytes around the first ASCII character.
 */
public final class YamlEncoding {
    private static final Charset UTF_32BE = Charset.forName("UTF-32BE");
    private static final Charset UTF_32LE = Charset.forName("UTF-32LE");

    private YamlEncoding() {
    }

    public static Charset detect(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        int b0 = byteAt(bytes, 0);
        int b1 = byteAt(bytes, 1);
        int b2 = byteAt(bytes, 2);
        int b3 = byteAt(bytes, 3);
        if (b0 == 0x00 && b1 == 0x00 && (b2 == 0xFE && b3 == 0xFF || b2 == 0x00 && b3 > 0)) {
            return UTF_32BE;
        }
        if (b0 == 0xFF && b1 == 0xFE && b2 == 0x00 && b3 == 0x00 || b0 > 0 && b1 == 0x00 && b2 == 0x00 && b3 == 0x00) {
            return UTF_32LE;
        }
        if (b0 == 0xFE && b1 == 0xFF || b0 == 0x00 && b1 > 0) {
            return StandardCharsets.UTF_16BE;
        }
        if (b0 == 0xFF && b1 == 0xFE || b0 > 0 && b1 == 0x00) {
            return StandardCharsets.UTF_16LE;
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * Decodes the bytes in their detected encoding; malformed input is rejected. A byte order mark
     * is kept as U+FEFF, which the parser accepts at the start of a document.
     */
    public static String decode(byte[] bytes) {
        Charset charset = detect(bytes);
        try {
            return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException exception) {
            throw new YamlParseException("YAML input is not valid " + charset.name());
        }
    }

    /**
     * Reads and decodes a stream, reading no more bytes than {@code maxCharacters} characters can take.
     */
    public static String read(InputStream input, int maxCharacters) {
        Objects.requireNonNull(input, "input");
        int maxBytes = maxBytes(maxCharacters);
        try {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new YamlParseException("YAML input exceeds configured size limit");
            }
            return decode(bytes);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read YAML input", exception);
        }
    }

    /**
     * Upper bound of the encoded size of {@code maxCharacters} characters plus a byte order mark.
     */
    static int maxBytes(int maxCharacters) {
        return (int) Math.min(Integer.MAX_VALUE - 16L, maxCharacters * 4L + 4);
    }

    private static int byteAt(byte[] bytes, int position) {
        return position < bytes.length ? bytes[position] & 0xFF : -1;
    }
}
