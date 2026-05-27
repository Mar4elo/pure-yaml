package io.pure.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
