# yaml

Lightweight YAML marshalling and unmarshalling for Java 21, with a deliberately safe, RFC 9512-oriented design.

This project focuses on:

- mapping Java records and POJOs to YAML
- parsing and writing a practical block-style YAML subset
- keeping the implementation small, readable, and dependency-free
- defaulting to safer behavior for `application/yaml`

It is a good fit for experiments, educational use, small services, configuration-oriented tooling, and projects that want a compact YAML stack without bringing in a large external dependency.

## Why This Project Exists

YAML is convenient, but full YAML support is also complicated:

- the format allows graphs, not just trees
- tag handling can become unsafe
- alias traversal and recursive structures can trigger resource-exhaustion problems
- interoperability across implementations is often weaker than people expect

RFC 9512 does not redefine the whole YAML language. Instead, it registers the `application/yaml` media type and highlights interoperability and security concerns around YAML processing.

This project takes that seriously and starts from a safer subset first:

- single-document input by default (multi-document streams only through `parseStream` with an explicit limit)
- block-style collections
- no implicit object construction
- no custom tag resolution: tags, anchors and other unsupported syntax are rejected instead of being read as text
- duplicate mapping keys are rejected
- alias nodes are parsed, but alias resolution is not enabled for Java object binding

That means the library is intentionally conservative today, with room to grow later.

## Features

### Current capabilities

- JDK 21
- Maven project
- no runtime dependencies
- YAML DOM-like model
- YAML parser for a practical block-style subset
- YAML writer
- Java object mapper
- records and POJOs
- generic collections and generic beans via `YamlTypeRef`
- untyped binding to `Object`, `Map<String, Object>` and `List<Object>` using the YAML 1.2 core schema
- common JDK value types (`UUID`, `URI`, `Path`, `java.time` types, `Locale`, ...)
- custom adapters via `TypeAdapter`
- annotations for naming, ignoring, creators, inclusion, and projected scalar values
- configurable processing limits, including limits on numeric literals
- cycle detection during object serialization
- cycle detection during YAML graph writing

### Supported Java binding types

- `String`
- `char` / `Character`
- `boolean` / `Boolean`
- primitive numeric types and boxed numeric types (decimal, `0x` hexadecimal, `0o` octal, `.inf`, `.nan`)
- `BigDecimal`
- `BigInteger`
- enums
- arrays
- `Collection`, `List`, `Set`, `SortedSet`, `Queue`, `Deque` and concrete collection classes
- `Map<K, ?>`, `SortedMap<K, ?>`, `EnumMap` and concrete map classes, where `K` is `String`, an enum,
  a boolean, a character, a number or one of the JDK value types below (keys are written as scalars)
- `Object` (untyped: maps, lists, strings, booleans, `Integer`/`Long`/`BigInteger`, `Double`)
- `Optional`
- `UUID`, `URI`, `Path`, `File`, `Instant`, `LocalDate`, `LocalTime`, `LocalDateTime`, `OffsetDateTime`,
  `OffsetTime`, `ZonedDateTime`, `Duration`, `Period`, `Year`, `YearMonth`, `MonthDay`, `ZoneId`, `ZoneOffset`,
  `Locale`, `Currency`, `Charset` (written as strings)
- Java records, including generic records
- POJOs with no-args constructor or `@YamlCreator`, including generic classes and generic superclasses

Other JDK types that are not accessible through reflection are rejected with a `YamlMappingException`;
register a `TypeAdapter` for them.

## What Is Supported Today

The current parser/writer supports a safe, practical subset of YAML:

- single YAML document (`Yaml.parse`), or several documents through `Yaml.parseStream` within `maxDocuments`
- `%YAML 1.x` directive
- document start marker `---`
- document end marker `...`
- block mappings and block sequences with any consistent indentation
- compact sequences under a mapping key (`key:` followed by `- item` at the same indentation)
- nested sequence entries on one line (`- - item`, `- key: value`)
- empty values (`key:` or `-`), read as null
- empty flow collections `[]` and `{}`
- scalar mapping keys
- plain scalars
- single-quoted scalars
- double-quoted scalars with the full YAML escape set (`\0`, `\n`, `\t`, `\xXX`, `\uXXXX`, `\UXXXXXXXX`, ...)
- comments
- UTF-8 text input
- alias nodes as parsed syntax objects

Input that falls outside this subset is rejected with a `YamlParseException` that names the line.
The parser never silently skips or reinterprets content.

## What Is Intentionally Not Supported Yet

These are currently rejected or intentionally left disabled:

- multi-document streams in `parse()` (use `parseStream()` with an explicit `maxDocuments`)
- non-empty flow collections like `[1, 2]` and `{a: 1}`
- block scalars (`|`, `>`) and multi-line plain or quoted scalars
- anchors (`&name`), tags (`!tag`) and `%TAG` directives
- complex mapping keys (`? key`)
- merge keys
- anchor resolution during Java binding
- custom tags for object construction
- implicit polymorphic deserialization
- non-scalar mapping keys and map keys of other Java types
- full YAML 1.2 grammar coverage

This is by design, not an accidental omission.

## Maven Coordinates

```xml
<dependency>
    <groupId>io.pure</groupId>
    <artifactId>yaml</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

If the project is not yet published to a repository, build and install it locally:

```bash
mvn install
```

## Requirements

- JDK 21
- Maven 3.9+

## Build

Run the full test suite:

```bash
mvn test
```

Build the JAR:

```bash
mvn package
```

## Quick Start

### Serialize a record

```java
import io.pure.yaml.binding.mapper.YamlMapper;

import java.util.List;

record User(String name, int age, List<String> roles) {
}

YamlMapper mapper = YamlMapper.createDefault();

String yaml = mapper.writeValueAsString(
    new User("Ann", 30, List.of("admin", "editor"))
);

System.out.println(yaml);
```

Output:

```yaml
name: "Ann"
age: 30
roles:
  - "admin"
  - "editor"
```

### Deserialize into a record

```java
import io.pure.yaml.binding.mapper.YamlMapper;

import java.util.List;

record User(String name, int age, List<String> roles) {
}

YamlMapper mapper = YamlMapper.createDefault();

User user = mapper.readValue("""
    name: "Ann"
    age: 30
    roles:
      - "admin"
      - "editor"
    """, User.class);
```

### Work with generic types

```java
import io.pure.yaml.binding.mapper.YamlMapper;
import io.pure.yaml.binding.mapper.YamlTypeRef;

import java.util.List;

record User(String name, int age) {
}

YamlMapper mapper = YamlMapper.createDefault();

List<User> users = mapper.readValue("""
    - name: "Ann"
      age: 30
    - name: "Bob"
      age: 41
    """, new YamlTypeRef<List<User>>() {});
```

### Read the YAML tree directly

```java
import io.pure.yaml.core.Yaml;
import io.pure.yaml.core.model.YamlMapping;
import io.pure.yaml.core.model.YamlScalar;

YamlMapping root = (YamlMapping) Yaml.parse("""
    service: "billing"
    port: 8080
    """);

String service = ((YamlScalar) root.get("service")).value();
String port = ((YamlScalar) root.get("port")).value();
```

### Write the YAML tree directly

```java
import io.pure.yaml.core.Yaml;
import io.pure.yaml.core.model.YamlMapping;
import io.pure.yaml.core.model.YamlScalar;
import io.pure.yaml.core.model.YamlSequence;

YamlMapping root = new YamlMapping();
root.put("name", new YamlScalar("demo"));

YamlSequence tags = new YamlSequence();
tags.add(new YamlScalar("alpha"));
tags.add(new YamlScalar("beta"));

root.put("tags", tags);

String yaml = Yaml.stringify(root);
```

## Annotations

### `@YamlProperty`

Rename a field, record component, or constructor parameter:

```java
import io.pure.yaml.annotation.YamlProperty;

record Account(
    @YamlProperty("account_id") String id,
    String owner
) {
}
```

### `@YamlIgnore`

Exclude a field or record component from binding. Ignored record components are not read from YAML
either: the constructor receives `null` (or the primitive default), and the key is treated as unknown.

```java
import io.pure.yaml.annotation.YamlIgnore;

final class Credentials {
    private String username;

    @YamlIgnore
    private String password;
}
```

### `@YamlCreator`

Choose a constructor for POJO creation. Parameter names come from `@YamlProperty` or, when the
code is compiled with `-parameters`, from the compiler. Without either, reading fails with a clear
error instead of silently passing `null`. Values passed to the creator are not overwritten afterwards,
so validation and normalization in the constructor are preserved.

```java
import io.pure.yaml.annotation.YamlCreator;
import io.pure.yaml.annotation.YamlProperty;

final class Person {
    private final String firstName;
    private final String lastName;

    @YamlCreator
    Person(
        @YamlProperty("first_name") String firstName,
        @YamlProperty("last_name") String lastName
    ) {
        this.firstName = firstName;
        this.lastName = lastName;
    }
}
```

### `@YamlInclude`

Skip `null` values during serialization. An explicit annotation on a field or type takes precedence
over `YamlMapperBuilder.defaultInclusion`, so `@YamlInclude(ALWAYS)` keeps a property even when the
default is `NON_NULL`:

```java
import io.pure.yaml.annotation.YamlInclude;

@YamlInclude(YamlInclude.Include.NON_NULL)
record Settings(String theme, String description) {
}
```

### `@YamlValue`

Serialize an object as a scalar projection:

```java
import io.pure.yaml.annotation.YamlValue;

enum Status {
    ACTIVE,
    DISABLED
}

final class AsValue {
    private final Status status;

    AsValue(Status status) {
        this.status = status;
    }

    @YamlValue
    public String raw() {
        return status.name().toLowerCase();
    }
}
```

Serialization result:

```yaml
"active"
```

## Custom Type Adapters

Use `TypeAdapter` when you want to override the default mapping logic for a specific type.

```java
import io.pure.yaml.binding.adapter.TypeAdapter;
import io.pure.yaml.binding.mapper.YamlMapper;
import io.pure.yaml.core.model.YamlNode;
import io.pure.yaml.core.model.YamlScalar;

import java.lang.reflect.Type;

record Token(String value) {
}

YamlMapper mapper = YamlMapper.createDefault();

mapper.registerAdapter(Token.class, new TypeAdapter<Token>() {
    @Override
    public YamlNode toYaml(Token value, YamlMapper mapper) {
        return new YamlScalar(value.value());
    }

    @Override
    public Token fromYaml(YamlNode node, Type type, YamlMapper mapper, String path) {
        return new Token(((YamlScalar) node).value());
    }
});
```

When writing, an adapter registered for a class or interface also applies to its subtypes, and it takes
precedence over built-in handling (including `String`, numbers and collections). When reading, an adapter
applies to the exact target type or its raw class.

The mapper also exposes lifecycle helpers:

- `registerAdapter(Type, TypeAdapter<?>)`
- `unregisterAdapter(Type)`
- `clearAdapters()`

## Configuration

You can customize mapper behavior through `YamlMapperBuilder`.

```java
import io.pure.yaml.annotation.YamlInclude;
import io.pure.yaml.binding.mapper.YamlMapper;
import io.pure.yaml.core.parser.YamlProcessingLimits;

YamlMapper mapper = YamlMapper.builder()
    .writeNulls(false)
    .failOnUnknownProperties(true)
    .defaultInclusion(YamlInclude.Include.NON_NULL)
    .coerceQuotedScalars(false)
    .processingLimits(new YamlProcessingLimits(
        64,      // maxDepth
        64,      // maxAliases
        1,       // maxDocuments
        500_000, // maxCharacters
        1_000    // maxNumberLength
    ))
    .build();
```

`coerceQuotedScalars` controls whether quoted values such as `"8080"` or `"true"` may be read into
numeric and boolean targets. It is enabled by default; disable it to require unquoted values, as the
YAML 1.2 core schema does.

### Processing limits

`YamlProcessingLimits` exists to reduce exposure to pathological input:

- `maxDepth`
- `maxAliases`
- `maxDocuments`
- `maxCharacters`
- `maxNumberLength`: maximum length of a numeric literal and maximum magnitude (in digits) of
  `BigInteger`/`BigDecimal` values, which protects against inputs such as `1e1000000000`

The defaults are conservative and suitable for many config-oriented use cases. The parser can also
be used with custom limits directly: `Yaml.parse(text, limits)`, `Yaml.parseStream(text, limits)`.

## Safety Model

This library intentionally defaults to safer behavior.

### Safe-by-default choices

- single-document parsing
- no alias resolution in Java binding
- no custom object construction from tags
- no code execution hooks
- configurable parser limits, including numeric literal limits
- duplicate mapping keys are rejected
- unsupported syntax is rejected instead of being read as text
- quoted `"null"` and `"~"` stay strings; only plain `null`, `Null`, `NULL`, `~` and empty values are null
- cycle detection during serialization

### Why this matters

RFC 9512 explicitly calls attention to risks around:

- multi-document streams
- recursive aliases and graph traversal
- non-JSON-compatible key structures
- unsafe tags
- resource exhaustion

This implementation tries to start from the more predictable side of that tradeoff.

## RFC 9512 Relationship

This project is **RFC 9512-oriented**, not a complete implementation of everything YAML can express.

That wording matters:

- RFC 9512 defines the `application/yaml` media type
- RFC 9512 discusses interoperability and security expectations
- RFC 9512 does **not** replace the full YAML language specification

In practice, this project uses RFC 9512 as the guiding safety and interoperability baseline, while implementing a narrower and more controlled subset of YAML behavior.

Reference:

- [RFC 9512 - The `application/yaml` Media Type](https://www.rfc-editor.org/rfc/rfc9512)

## Architecture

The code is split into three main areas.

### `io.pure.yaml.core`

Low-level YAML functionality:

- parser
- writer
- processing limits
- object model for YAML nodes

Key types:

- `Yaml`
- `YamlException`
- `YamlParser`
- `YamlWriter`
- `YamlNode`
- `YamlScalar`
- `YamlSequence`
- `YamlMapping`
- `YamlAlias`
- `YamlDocument`
- `YamlStream`

### `io.pure.yaml.binding`

Java object mapping:

- `YamlMapper`
- `YamlMapperBuilder`
- `YamlMapperConfig`
- `YamlTypeRef`
- `TypeAdapter`
- reflection-based introspection for records and POJOs

### `io.pure.yaml.annotation`

Binding annotations:

- `@YamlProperty`
- `@YamlIgnore`
- `@YamlCreator`
- `@YamlValue`
- `@YamlInclude`

## Error Handling

All exceptions extend `io.pure.yaml.core.YamlException`:

- `YamlParseException` for syntax and parser-limit failures
- `YamlWriteException` for invalid YAML graph writing
- `YamlMappingException` for Java binding failures

Typical binding errors include:

- unknown properties
- unsupported collection/map targets
- invalid boolean lexemes for YAML 1.2 binding
- invalid, out-of-range or oversized numbers
- invalid values for JDK value types (dates, UUIDs, ...)
- types that are not accessible through reflection
- alias resolution attempts where disabled
- cyclic object graphs

## Testing

The project includes tests for:

- records and POJOs
- generic collections and generic beans
- write/read round trips of nested collections, empty collections and special strings
- randomized round trips of YAML trees and fuzzing of the parser
- rejection of unsupported or malformed syntax
- quoted scalars and escapes
- YAML directives and document markers
- BOM and comments
- parser limits
- alias parsing
- cycle rejection
- custom adapters
- strict boolean/null handling

Run the tests:

```bash
mvn test
```

`mvn verify` additionally runs the build checks used in CI:

- Maven Enforcer: JDK 21+, Maven 3.9+ and pinned plugin versions
- JaCoCo: coverage report in `target/site/jacoco` and minimum line/branch coverage
- SpotBugs: static analysis, with justified exclusions in `spotbugs-exclude.xml`

## Limitations

Before using this library in production, be aware of the current scope:

- not a full YAML 1.2 implementation
- not yet a general-purpose replacement for mature YAML libraries
- only empty flow-style collections (`[]`, `{}`)
- no block scalars or multi-line scalars
- no merge-key support
- no tag-driven object model
- no alias resolution for object binding
- no streaming read/write API for very large documents

## Roadmap

Planned directions include:

1. anchors and aliases as first-class graph resolution
2. flow collections, block scalars and multi-line scalars
3. merge-key policy support
4. broader YAML 1.2 compatibility
5. additional performance tuning for large inputs
6. clearer module publishing story and versioning discipline

## Contributing

Contributions are welcome, especially in these areas:

- parser correctness
- compatibility tests
- documentation
- performance benchmarks
- additional safe YAML features

If you propose a new feature, it helps to describe:

- whether it is part of the current safe subset
- whether it changes default security behavior
- whether it should be opt-in or enabled by default

## Project Status

The project is functional and tested, but still evolving.

Today it should be viewed as:

- stable enough for experimentation and small controlled use cases
- explicit about its limitations
- intentionally conservative in scope

It is not yet claiming full YAML compatibility.

## License

This project is licensed under the Apache License 2.0.

See the [LICENSE](LICENSE) file for the full license text.
