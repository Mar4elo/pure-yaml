package io.pure.yaml.binding.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The single-pass classifier must agree with the regular expressions of the YAML 1.2 core schema
 * that it replaces (YAML 1.2.2, 10.3.2).
 */
class CoreSchemaTest {
    private static final Pattern INTEGER = Pattern.compile("[-+]?[0-9]+");
    private static final Pattern OCTAL = Pattern.compile("0o[0-7]+");
    private static final Pattern HEXADECIMAL = Pattern.compile("0x[0-9a-fA-F]+");
    private static final Pattern FLOAT = Pattern.compile("[-+]?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)([eE][-+]?[0-9]+)?");
    private static final Pattern INFINITY = Pattern.compile("[-+]?\\.(inf|Inf|INF)");
    private static final Pattern NOT_A_NUMBER = Pattern.compile("\\.(nan|NaN|NAN)");
    private static final String ALPHABET = "0123456789+-.eExXoOabfinINFaAnNt ";

    @Test
    void agreesWithTheCoreSchemaExpressions() {
        List<String> samples = List.of("", "0", "-0", "+12", "007", "0x", "0x1F", "0xg", "0o", "0o17", "0o8", "-0x1", "1.", ".5", ".",
            "1e3", "1E+3", "1e", "1e+", "-.inf", "+.Inf", ".INF", ".inf", "-.nan", ".NaN", ".nan", "1_000", "12:30", "inf", "nan",
            "1.5e-3", "--1", "+", "-", "0.0", "1..2", "e3", ".e3", "1e3.5");
        samples.forEach(sample -> assertEquals(expected(sample), CoreSchema.classify(sample), sample));
        Random random = new Random(1012);
        for (int i = 0; i < 200_000; i++) {
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(7);
            for (int j = 0; j < length; j++) {
                text.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            String sample = text.toString();
            assertEquals(expected(sample), CoreSchema.classify(sample), sample);
        }
    }

    private static CoreSchema.Kind expected(String text) {
        if (INTEGER.matcher(text).matches()) {
            return CoreSchema.Kind.DECIMAL_INTEGER;
        }
        if (HEXADECIMAL.matcher(text).matches()) {
            return CoreSchema.Kind.HEX_INTEGER;
        }
        if (OCTAL.matcher(text).matches()) {
            return CoreSchema.Kind.OCTAL_INTEGER;
        }
        if (INFINITY.matcher(text).matches()) {
            return CoreSchema.Kind.INFINITY;
        }
        if (NOT_A_NUMBER.matcher(text).matches()) {
            return CoreSchema.Kind.NAN;
        }
        if (FLOAT.matcher(text).matches()) {
            return CoreSchema.Kind.FLOAT;
        }
        return CoreSchema.Kind.OTHER;
    }
}
