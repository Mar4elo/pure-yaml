package io.pure.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;

import io.pure.yaml.annotation.YamlCreator;
import io.pure.yaml.annotation.YamlIgnore;
import io.pure.yaml.annotation.YamlInclude;
import io.pure.yaml.annotation.YamlProperty;
import io.pure.yaml.binding.mapper.YamlMapper;
import io.pure.yaml.binding.mapper.YamlMapperConfig;
import io.pure.yaml.binding.mapper.YamlMappingException;
import io.pure.yaml.binding.mapper.YamlTypeRef;
import io.pure.yaml.core.model.YamlMapping;
import io.pure.yaml.core.model.YamlNode;
import io.pure.yaml.core.model.YamlScalar;
import io.pure.yaml.core.parser.YamlProcessingLimits;

class YamlMapperTest {
    private final YamlMapper mapper = YamlMapper.createDefault();

    @Test
    void serializesAndDeserializesRecord() {
        User user = new User("Ann", 30, List.of("admin", "editor"));
        String yaml = mapper.writeValueAsString(user);
        User roundTrip = mapper.readValue(yaml, User.class);
        assertEquals(user, roundTrip);
    }

    @Test
    void serializesPojoWithAnnotations() {
        Account account = new Account("A-1", "secret", new BigDecimal("12.50"));
        String yaml = mapper.writeValueAsString(account);
        assertEquals("""
            account_id: "A-1"
            balance: 12.50""", yaml);
    }

    @Test
    void deserializesPojoWithYamlCreator() {
        Person person = mapper.readValue("""
            first_name: "Ada"
            last_name: "Lovelace"
            """, Person.class);
        assertEquals("Ada", person.firstName());
        assertEquals("Lovelace", person.lastName());
    }

    @Test
    void supportsGenericCollections() {
        List<User> users = mapper.readValue("""
            - name: "Ann"
              age: 30
              roles:
                - "admin"
            """, new YamlTypeRef<List<User>>() {
        });
        assertEquals(1, users.size());
        assertEquals("Ann", users.getFirst().name());
    }

    @Test
    void supportsMapsAndOptionalValues() {
        YamlMapper localMapper = YamlMapper.builder().writeNulls(false).build();
        Settings settings = new Settings(Map.of("theme", "light"), Optional.empty());
        String yaml = localMapper.writeValueAsString(settings);
        Settings roundTrip = localMapper.readValue(yaml, Settings.class);
        assertTrue(roundTrip.flags().containsKey("theme"));
        assertFalse(roundTrip.description().isPresent());
    }

    @Test
    void reportsUnknownProperties() {
        YamlMappingException exception = assertThrows(
            YamlMappingException.class,
            () -> mapper.readValue("""
                name: "Ann"
                age: 30
                roles:
                  - "admin"
                extra: 1
                """, User.class)
        );
        assertTrue(exception.getMessage().contains("Unknown property 'extra'"));
    }

    @Test
    void canReadYamlTreeDirectly() {
        YamlMapping object = mapper.readValue("""
            a: 1
            """, YamlMapping.class);
        assertTrue(object.containsKey("a"));
    }

    @Test
    void rejectsCyclicObjectGraphsDuringSerialization() {
        Node node = new Node();
        node.next = node;

        YamlMappingException exception = assertThrows(YamlMappingException.class, () -> mapper.writeValueAsString(node));
        assertTrue(exception.getMessage().contains("Cyclic object graph"));
    }

    @Test
    void readsYamlBooleanAndNullLexemes() {
        Flags flags = mapper.readValue("""
            enabled: TRUE
            disabled: false
            note: ~
            """, Flags.class);

        assertTrue(flags.enabled());
        assertFalse(flags.disabled());
        assertTrue(flags.note().isEmpty());
    }

    @Test
    void rejectsAliasResolutionForTypedBindingByDefault() {
        YamlMappingException exception = assertThrows(
            YamlMappingException.class,
            () -> mapper.readValue("""
                value: *shared
                """, AliasHolder.class)
        );
        assertTrue(exception.getMessage().contains("Alias resolution is not enabled"));
    }

    @Test
    void canReadAliasNodeIntoYamlTree() {
        YamlNode node = mapper.readValue("""
            value: *shared
            """, YamlNode.class);

        assertTrue(node instanceof YamlMapping);
        assertTrue(((YamlMapping) node).get("value") instanceof io.pure.yaml.core.model.YamlAlias);
    }

    @Test
    void rejectsUnsupportedBooleanLexemeForYaml12Binding() {
        YamlMappingException exception = assertThrows(
            YamlMappingException.class,
            () -> mapper.readValue("""
                enabled: yes
                disabled: false
                note: null
                """, Flags.class)
        );
        assertTrue(exception.getMessage().contains("Expected YAML 1.2 boolean"));
    }

    @Test
    void supportsYamlValueProjection() {
        String yaml = mapper.writeValueAsString(new AsValue(Status.ACTIVE));
        assertEquals("\"active\"", yaml);
    }

    @Test
    void supportsCustomTypeAdapterLifecycle() {
        YamlMapper localMapper = YamlMapper.createDefault();
        localMapper.registerAdapter(
            Token.class,
            new io.pure.yaml.binding.adapter.TypeAdapter<Token>() {
                @Override
                public YamlNode toYaml(Token value, YamlMapper mapper) {
                    return new YamlScalar(value.value());
                }

                @Override
                public Token fromYaml(YamlNode node, java.lang.reflect.Type type, YamlMapper mapper, String path) {
                    return new Token(((YamlScalar) node).value());
                }
            }
        );

        assertEquals("abc", localMapper.readValue(localMapper.writeValueAsString(new Token("abc")), Token.class).value());
        localMapper.unregisterAdapter(Token.class);
        assertThrows(YamlMappingException.class, () -> localMapper.readValue("\"abc\"", Token.class));
    }

    @Test
    void respectsStrictProcessingLimitsFromConfiguration() {
        YamlMapper limitedMapper = YamlMapper.builder()
            .processingLimits(new YamlProcessingLimits(1, 8, 1, 1_000))
            .build();

        assertThrows(
            io.pure.yaml.core.parser.YamlParseException.class,
            () -> limitedMapper.readValue("""
                root:
                  child:
                    leaf: 1
                """, YamlNode.class)
        );
    }

    @Test
    void roundTripsNestedCollectionsOfObjects() {
        Team team = new Team(
            List.of(new User("Ann", 30, List.of("admin")), new User("Bob", 41, List.of())),
            List.of(List.of("a", "b"), List.of()),
            Map.of()
        );
        String yaml = mapper.writeValueAsString(team);
        assertEquals("""
            members:
              - name: "Ann"
                age: 30
                roles:
                  - "admin"
              - name: "Bob"
                age: 41
                roles: []
            matrix:
              - - "a"
                - "b"
              - []
            labels: {}""", yaml);
        assertEquals(team, mapper.readValue(yaml, Team.class));
    }

    @Test
    void keepsQuotedNullLikeStrings() {
        for (String value : List.of("null", "~", "Null", "true", "123", "", " padded ")) {
            Text text = mapper.readValue(mapper.writeValueAsString(new Text(value)), Text.class);
            assertEquals(value, text.value());
        }
        assertNull(mapper.readValue("value: null", Text.class).value());
        assertNull(mapper.readValue("value:", Text.class).value());
    }

    @Test
    void roundTripsStringsWithSpecialCharacters() {
        for (String value : List.of("say \" #hi", "it's # here", "a: b", "line\nbreak", "tab\there", "back\\slash", "- item", "*star", "\u0001")) {
            assertEquals(value, mapper.readValue(mapper.writeValueAsString(new Text(value)), Text.class).value());
        }
    }

    @Test
    void writesAndReadsSpecialFloatingPointValues() {
        Floats floats = new Floats(Double.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0.1f);
        String yaml = mapper.writeValueAsString(floats);
        assertEquals("nan: .nan\npositive: .inf\nnegative: -.inf\nsmall: 0.1", yaml);
        assertEquals(floats, mapper.readValue(yaml, Floats.class));
    }

    @Test
    void readsYamlCoreSchemaNumbers() {
        Numbers numbers = mapper.readValue("""
            hex: 0x1F
            octal: 0o17
            exponent: 1e3
            decimal: .5
            """, Numbers.class);
        assertEquals(new Numbers(31, 15L, 1000, 0.5), numbers);
    }

    @Test
    void reportsInvalidNumbersAsMappingErrors() {
        YamlMappingException invalid = assertThrows(YamlMappingException.class, () -> mapper.readValue("value: abc", IntHolder.class));
        assertTrue(invalid.getMessage().contains("Invalid number at $.value"));
        assertThrows(YamlMappingException.class, () -> mapper.readValue("value: 1.5", IntHolder.class));
        assertThrows(YamlMappingException.class, () -> mapper.readValue("value: 1e999999999999", IntHolder.class));
        assertThrows(YamlMappingException.class, () -> mapper.readValue("value: 1e400", DoubleHolder.class));
    }

    @Test
    void rejectsNumbersThatAreTooExpensiveToExpand() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertThrows(YamlMappingException.class, () -> mapper.readValue("value: 1e1000000000", BigIntegerHolder.class));
            assertThrows(YamlMappingException.class, () -> mapper.readValue("value: 1e1000000000", BigDecimalHolder.class));
            assertThrows(YamlMappingException.class, () -> mapper.readValue("value: 1e1000000000", IntHolder.class));
            assertThrows(YamlMappingException.class, () -> mapper.readValue("value: " + "9".repeat(1_001), BigIntegerHolder.class));
        });
        assertEquals(new BigInteger("1" + "0".repeat(100)), mapper.readValue("value: 1e100", BigIntegerHolder.class).value());
    }

    @Test
    void readsUntypedValues() {
        Map<String, Object> values = mapper.readValue("""
            string: text
            quoted: "123"
            integer: 42
            long: 9000000000
            big: 99999999999999999999
            double: 1.5
            bool: true
            nothing: null
            list:
              - 1
              - nested: x
            """, new YamlTypeRef<Map<String, Object>>() {
        });
        assertEquals("text", values.get("string"));
        assertEquals("123", values.get("quoted"));
        assertEquals(42, values.get("integer"));
        assertEquals(9_000_000_000L, values.get("long"));
        assertEquals(new BigInteger("99999999999999999999"), values.get("big"));
        assertEquals(1.5, values.get("double"));
        assertEquals(true, values.get("bool"));
        assertTrue(values.containsKey("nothing"));
        assertNull(values.get("nothing"));
        assertEquals(List.of(1, Map.of("nested", "x")), values.get("list"));
        assertInstanceOf(LinkedHashMap.class, mapper.readValue("a: 1", Object.class));
    }

    @Test
    void resolvesGenericBeanProperties() {
        Box<User> box = mapper.readValue("""
            value:
              name: "Ann"
              age: 30
              roles: []
            items:
              - name: "Bob"
                age: 41
                roles: []
            """, new YamlTypeRef<Box<User>>() {
        });
        assertEquals("Ann", box.value().name());
        assertEquals("Bob", box.items().getFirst().name());

        UserPage page = mapper.readValue("""
            content:
              - name: "Ann"
                age: 30
                roles: []
            """, UserPage.class);
        assertEquals("Ann", page.content.getFirst().name());

        Box<?> raw = mapper.readValue("value: 1\nitems: []", Box.class);
        assertEquals(1, raw.value());
    }

    @Test
    void supportsCommonJdkValueTypes() {
        JdkValues values = new JdkValues(
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
            URI.create("https://example.com/a?b=c"),
            LocalDate.of(2026, 10, 11),
            Instant.parse("2026-10-11T10:15:30Z"),
            Duration.ofMinutes(90),
            ZoneId.of("Europe/Paris"),
            Locale.forLanguageTag("ru-RU")
        );
        String yaml = mapper.writeValueAsString(values);
        assertTrue(yaml.contains("duration: \"PT1H30M\""));
        assertEquals(values, mapper.readValue(yaml, JdkValues.class));

        YamlMappingException invalid = assertThrows(YamlMappingException.class, () -> mapper.readValue("date: 2026-13-01", DateHolder.class));
        assertTrue(invalid.getMessage().contains("Invalid LocalDate value at $.date"));
    }

    @Test
    void reportsInaccessibleTypesAsMappingErrors() {
        YamlMappingException exception = assertThrows(YamlMappingException.class, () -> mapper.writeValueAsString(new ReentrantLock()));
        assertTrue(exception.getMessage().contains("register a TypeAdapter"));
    }

    @Test
    void rejectsDuplicateKeys() {
        assertThrows(io.pure.yaml.core.parser.YamlParseException.class, () -> mapper.readValue("""
            name: "Ann"
            age: 30
            name: "Bob"
            roles: []
            """, User.class));
    }

    @Test
    void doesNotReadIgnoredRecordComponents() {
        assertThrows(YamlMappingException.class, () -> mapper.readValue("user: u\nrole: admin", Secret.class));

        YamlMapper lenient = YamlMapper.builder().failOnUnknownProperties(false).build();
        Secret secret = lenient.readValue("user: u\nrole: admin\nlevel: 9", Secret.class);
        assertEquals(new Secret("u", null, 0), secret);
        assertEquals("user: \"u\"", mapper.writeValueAsString(new Secret("u", "admin", 9)));
    }

    @Test
    void doesNotOverwriteCreatorResultWithReflection() {
        Normalized normalized = mapper.readValue("""
            name: "ann"
            note: "kept"
            """, Normalized.class);
        assertEquals("ANN", normalized.name);
        assertEquals("kept", normalized.note);
    }

    @Test
    void usesCompiledParameterNamesForCreators() {
        assertEquals("Ada", mapper.readValue("name: \"Ada\"", NamedByCompiler.class).name);
    }

    @Test
    void escapesMapKeys() {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("x\n- injected", 1);
        map.put("*k", 2);
        map.put("a: b", 3);
        map.put("", 4);
        String yaml = mapper.writeValueAsString(map);
        assertEquals(map, mapper.readValue(yaml, new YamlTypeRef<Map<String, Integer>>() {
        }));
    }

    @Test
    void readsEmptyAndNullDocumentsAsYamlNodes() {
        assertInstanceOf(YamlScalar.class, mapper.readValue("", YamlNode.class));
        assertEquals("null", mapper.readValue("null", YamlScalar.class).value());
        assertNull(mapper.readValue("", String.class));
        assertThrows(YamlMappingException.class, () -> mapper.readValue("null", YamlMapping.class));
    }

    @Test
    void readsPrimitiveTargetsAndRawOptionals() {
        assertEquals(5, mapper.readValue("5", int.class));
        assertEquals(Optional.of("x"), mapper.readValue("value: x", RawOptional.class).value());
    }

    @Test
    void appliesWriteAdaptersToSubtypesAndBuiltInTypes() {
        YamlMapper localMapper = YamlMapper.createDefault();
        localMapper.registerAdapter(CharSequence.class, new io.pure.yaml.binding.adapter.TypeAdapter<CharSequence>() {
            @Override
            public YamlNode toYaml(CharSequence value, YamlMapper mapper) {
                return YamlScalar.plain(value.toString().toUpperCase(Locale.ROOT));
            }

            @Override
            public CharSequence fromYaml(YamlNode node, java.lang.reflect.Type type, YamlMapper mapper, String path) {
                return ((YamlScalar) node).value();
            }
        });
        assertEquals("value: ABC", localMapper.writeValueAsString(new Text("abc")));

        localMapper.registerAdapter(List.class, new io.pure.yaml.binding.adapter.TypeAdapter<List<?>>() {
            @Override
            public YamlNode toYaml(List<?> value, YamlMapper mapper) {
                return YamlScalar.plain(String.valueOf(value.size()));
            }

            @Override
            public List<?> fromYaml(YamlNode node, java.lang.reflect.Type type, YamlMapper mapper, String path) {
                return List.of(((YamlScalar) node).value());
            }
        });
        assertEquals(List.of("3"), localMapper.readValue("3", new YamlTypeRef<List<String>>() {
        }));
    }

    @Test
    void explicitAlwaysOverridesNonNullDefault() {
        YamlMapper nonNull = YamlMapper.builder().defaultInclusion(YamlInclude.Include.NON_NULL).build();
        assertEquals("always: null", nonNull.writeValueAsString(new Inclusion(null, null)));
    }

    @Test
    void instantiatesSpecificCollectionAndMapTypes() {
        SpecificTypes types = mapper.readValue("""
            sorted:
              - b
              - a
            queue:
              - 1
            tree:
              b: 2
              a: 1
            """, SpecificTypes.class);
        assertEquals(List.of("a", "b"), List.copyOf(types.sorted()));
        assertEquals(1, types.queue().getFirst());
        assertEquals(List.of("a", "b"), List.copyOf(types.tree().keySet()));
    }

    @Test
    void supportsScalarMapKeyTypes() {
        EnumMap<Status, Integer> byStatus = new EnumMap<>(Status.class);
        byStatus.put(Status.ACTIVE, 1);
        KeyedMaps maps = new KeyedMaps(
            byStatus,
            new TreeMap<>(Map.of(10, "ten", -1, "minus one")),
            Map.of(true, "yes"),
            Map.of(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), 'x'),
            Map.of(LocalDate.of(2026, 10, 11), 0.5)
        );
        String yaml = mapper.writeValueAsString(maps);
        assertTrue(yaml.contains("byStatus:\n  ACTIVE: 1"));
        assertTrue(yaml.contains("byNumber:\n  -1: \"minus one\"\n  10: \"ten\""));
        KeyedMaps roundTrip = mapper.readValue(yaml, KeyedMaps.class);
        assertEquals(maps, roundTrip);
        assertInstanceOf(EnumMap.class, roundTrip.byStatus());
    }

    @Test
    void rejectsInvalidOrCollidingMapKeys() {
        assertThrows(YamlMappingException.class, () -> mapper.readValue("byNumber:\n  x: a", NumberKeys.class));
        assertThrows(YamlMappingException.class, () -> mapper.readValue("byNumber:\n  null: a", NumberKeys.class));
        YamlMappingException duplicate = assertThrows(
            YamlMappingException.class,
            () -> mapper.readValue("byNumber:\n  1: a\n  01: b", NumberKeys.class)
        );
        assertTrue(duplicate.getMessage().contains("Duplicate map key"));
        assertThrows(YamlMappingException.class, () -> mapper.writeValueAsString(Map.of(new Object(), 1)));
        assertThrows(YamlMappingException.class, () -> mapper.readValue("a: 1", new YamlTypeRef<Map<Object[], Integer>>() {
        }));
    }

    @Test
    void canRequireUnquotedBooleansAndNumbers() {
        assertEquals(8080, mapper.readValue("value: \"8080\"", IntHolder.class).value());

        YamlMapper strict = YamlMapper.builder().coerceQuotedScalars(false).build();
        assertEquals(8080, strict.readValue("value: 8080", IntHolder.class).value());
        YamlMappingException quoted = assertThrows(YamlMappingException.class, () -> strict.readValue("value: \"8080\"", IntHolder.class));
        assertTrue(quoted.getMessage().contains("Expected an unquoted scalar at $.value"));
        assertThrows(YamlMappingException.class, () -> strict.readValue("enabled: 'true'\ndisabled: false\nnote: x", Flags.class));

        User user = new User("Ann", 30, List.of("admin"));
        assertEquals(user, strict.readValue(strict.writeValueAsString(user), User.class));
    }

    @Test
    void preservesBigDecimalPrecisionAndScale() {
        BigDecimalHolder holder = new BigDecimalHolder(new BigDecimal("1E+100"));
        String yaml = mapper.writeValueAsString(holder);
        assertEquals("value: 1E+100", yaml);
        assertEquals(holder, mapper.readValue(yaml, BigDecimalHolder.class));
    }

    record KeyedMaps(
        EnumMap<Status, Integer> byStatus,
        TreeMap<Integer, String> byNumber,
        Map<Boolean, String> byFlag,
        Map<UUID, Character> byId,
        Map<LocalDate, Double> byDate
    ) {
    }

    record NumberKeys(Map<Integer, String> byNumber) {
    }

    record Team(List<User> members, List<List<String>> matrix, Map<String, String> labels) {
    }

    record Text(String value) {
    }

    record Floats(double nan, double positive, float negative, float small) {
    }

    record Numbers(int hex, long octal, int exponent, double decimal) {
    }

    record IntHolder(int value) {
    }

    record DoubleHolder(double value) {
    }

    record BigIntegerHolder(BigInteger value) {
    }

    record BigDecimalHolder(BigDecimal value) {
    }

    record Box<T>(T value, List<T> items) {
    }

    static class Page<T> {
        List<T> content;
    }

    static final class UserPage extends Page<User> {
    }

    record JdkValues(UUID id, URI uri, LocalDate date, Instant instant, Duration duration, ZoneId zone, Locale locale) {
    }

    record DateHolder(LocalDate date) {
    }

    record Secret(String user, @YamlIgnore String role, @YamlIgnore int level) {
    }

    static final class Normalized {
        @YamlProperty("name")
        private final String name;
        private String note;

        @YamlCreator
        Normalized(@YamlProperty("name") String name) {
            this.name = name.toUpperCase(Locale.ROOT);
        }
    }

    static final class NamedByCompiler {
        private final String name;

        @YamlCreator
        NamedByCompiler(String name) {
            this.name = name;
        }
    }

    @SuppressWarnings("rawtypes")
    record RawOptional(Optional value) {
    }

    record Inclusion(@YamlInclude(YamlInclude.Include.ALWAYS) String always, String other) {
    }

    record SpecificTypes(SortedSet<String> sorted, Deque<Integer> queue, TreeMap<String, Integer> tree) {
    }

    record User(String name, int age, List<String> roles) {
    }

    @YamlInclude(YamlInclude.Include.NON_NULL)
    static final class Account {
        @YamlProperty("account_id")
        private final String id;
        @YamlIgnore
        private final String secret;
        private final BigDecimal balance;

        Account(String id, String secret, BigDecimal balance) {
            this.id = id;
            this.secret = secret;
            this.balance = balance;
        }
    }

    static final class Person {
        private final String firstName;
        private final String lastName;

        @YamlCreator
        Person(@YamlProperty("first_name") String firstName, @YamlProperty("last_name") String lastName) {
            this.firstName = firstName;
            this.lastName = lastName;
        }

        String firstName() {
            return firstName;
        }

        String lastName() {
            return lastName;
        }
    }

    record Settings(Map<String, String> flags, Optional<String> description) {
    }

    record Flags(boolean enabled, boolean disabled, Optional<String> note) {
    }

    record AliasHolder(String value) {
    }

    record Token(String value) {
    }

    enum Status {
        ACTIVE,
        DISABLED
    }

    static final class AsValue {
        private final Status status;

        AsValue(Status status) {
            this.status = status;
        }

        @io.pure.yaml.annotation.YamlValue
        public String raw() {
            return status.name().toLowerCase();
        }
    }

    static final class Node {
        private Node next;
    }
}
