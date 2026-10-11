package io.pure.yaml.binding.mapper;

/**
 * Single-pass classification of plain scalars by the YAML 1.2 core schema (YAML 1.2.2, 10.3.2),
 * replacing regular expressions.
 */
final class CoreSchema {
    enum Kind { DECIMAL_INTEGER, HEX_INTEGER, OCTAL_INTEGER, FLOAT, INFINITY, NAN, OTHER }

    private CoreSchema() {
    }

    static Kind classify(String text) {
        int length = text.length();
        if (length == 0) {
            return Kind.OTHER;
        }
        char first = text.charAt(0);
        if (first == '0' && length > 2) {
            char prefix = text.charAt(1);
            if (prefix == 'x') {
                return allDigits(text, 2, 16) ? Kind.HEX_INTEGER : Kind.OTHER;
            }
            if (prefix == 'o') {
                return allDigits(text, 2, 8) ? Kind.OCTAL_INTEGER : Kind.OTHER;
            }
        }
        int position = first == '-' || first == '+' ? 1 : 0;
        if (position == length) {
            return Kind.OTHER;
        }
        if (text.charAt(position) == '.' && length - position == 4) {
            if (matchesAny(text, position + 1, ".inf", ".Inf", ".INF")) {
                return Kind.INFINITY;
            }
            if (position == 0 && matchesAny(text, 1, ".nan", ".NaN", ".NAN")) {
                return Kind.NAN;
            }
        }
        int integerDigits = 0;
        while (position < length && isDigit(text.charAt(position))) {
            position++;
            integerDigits++;
        }
        boolean fraction = false;
        int fractionDigits = 0;
        if (position < length && text.charAt(position) == '.') {
            fraction = true;
            position++;
            while (position < length && isDigit(text.charAt(position))) {
                position++;
                fractionDigits++;
            }
        }
        if (integerDigits + fractionDigits == 0) {
            return Kind.OTHER;
        }
        boolean exponent = false;
        if (position < length && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
            position++;
            if (position < length && (text.charAt(position) == '-' || text.charAt(position) == '+')) {
                position++;
            }
            int exponentDigits = 0;
            while (position < length && isDigit(text.charAt(position))) {
                position++;
                exponentDigits++;
            }
            if (exponentDigits == 0) {
                return Kind.OTHER;
            }
            exponent = true;
        }
        if (position != length) {
            return Kind.OTHER;
        }
        return fraction || exponent ? Kind.FLOAT : Kind.DECIMAL_INTEGER;
    }

    static boolean isInteger(Kind kind) {
        return kind == Kind.DECIMAL_INTEGER || kind == Kind.HEX_INTEGER || kind == Kind.OCTAL_INTEGER;
    }

    static boolean isNumber(Kind kind) {
        return kind != Kind.OTHER;
    }

    /**
     * Whether the three letters at {@code from} match one of the spellings (given with their leading dot).
     */
    private static boolean matchesAny(String text, int from, String... spellings) {
        for (String spelling : spellings) {
            if (text.regionMatches(from, spelling, 1, 3)) {
                return true;
            }
        }
        return false;
    }

    private static boolean allDigits(String text, int from, int radix) {
        for (int i = from; i < text.length(); i++) {
            if (Character.digit(text.charAt(i), radix) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDigit(char ch) {
        return ch >= '0' && ch <= '9';
    }
}
