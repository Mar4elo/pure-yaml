package io.pure.yaml;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.pure.yaml.binding.mapper.YamlMapper;
import io.pure.yaml.binding.mapper.YamlTypeRef;
import io.pure.yaml.core.Yaml;
import io.pure.yaml.core.model.YamlMapping;
import io.pure.yaml.core.model.YamlScalar;
import io.pure.yaml.core.parser.YamlEncoding;
import io.pure.yaml.core.parser.YamlParseException;
import io.pure.yaml.core.parser.YamlProcessingLimits;

class YamlEncodingTest {
    // Without a byte order mark a stream must start with an ASCII character (YAML 1.2.2, 5.2).
    private static final String TEXT = "# encoded\nключ: значение 😀\nlist: [a, b]\n";

    @Test
    void detectsAndDecodesEveryYamlEncoding() {
        for (String name : List.of("UTF-8", "UTF-16BE", "UTF-16LE", "UTF-32BE", "UTF-32LE")) {
            Charset charset = Charset.forName(name);
            for (boolean bom : List.of(false, true)) {
                byte[] bytes = encode(TEXT, charset, bom);
                assertEquals(charset, YamlEncoding.detect(bytes), name + (bom ? " with BOM" : ""));
                YamlMapping mapping = (YamlMapping) Yaml.parse(bytes);
                assertEquals("значение 😀", ((YamlScalar) mapping.get("ключ")).value(), name);
            }
        }
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(YamlParseException.class, () -> Yaml.parse(new byte[]{'a', ':', ' ', (byte) 0xC3}));
        assertThrows(YamlParseException.class, () -> Yaml.parse(new byte[]{(byte) 0xFE, (byte) 0xFF, 0x00}));
    }

    @Test
    void enforcesTheSizeLimitBeforeDecoding() {
        YamlProcessingLimits limits = new YamlProcessingLimits(16, 16, 1, 10);
        byte[] large = "a: ".concat("x".repeat(100)).getBytes(StandardCharsets.UTF_8);
        assertThrows(YamlParseException.class, () -> Yaml.parse(large, limits));
        assertThrows(YamlParseException.class, () -> Yaml.parseStream(new ByteArrayInputStream(large), limits));
        assertEquals(1, Yaml.parseStream(new ByteArrayInputStream("a: b".getBytes(StandardCharsets.UTF_8)), limits).documents().size());
    }

    @Test
    void readsAndWritesBytesWithTheMapper() {
        YamlMapper mapper = YamlMapper.createDefault();
        Map<String, String> value = Map.of("name", "Ann");
        byte[] bytes = mapper.writeValueAsBytes(value);
        assertArrayEquals("name: \"Ann\"".getBytes(StandardCharsets.UTF_8), bytes);
        YamlTypeRef<Map<String, String>> type = new YamlTypeRef<>() {
        };
        assertEquals(value, mapper.readValue(encode("name: Ann", StandardCharsets.UTF_16LE, true), type));
        assertEquals(value, mapper.readValue(new ByteArrayInputStream(encode("name: Ann", Charset.forName("UTF-32BE"), false)), type));
    }

    private static byte[] encode(String text, Charset charset, boolean bom) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (bom) {
            output.writeBytes("﻿".getBytes(charset));
        }
        output.writeBytes(text.getBytes(charset));
        return output.toByteArray();
    }
}
