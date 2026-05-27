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

- single-document input
- block-style collections
- no implicit object construction
- no custom tag resolution
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
- generic collections via `YamlTypeRef`
- custom adapters via `TypeAdapter`
- annotations for naming, ignoring, creators, inclusion, and projected scalar values
- configurable processing limits
- cycle detection during object serialization
- cycle detection during YAML graph writing

### Supported Java binding types

- `String`
- `char` / `Character`
- `boolean` / `Boolean`
- primitive numeric types and boxed numeric types
- `BigDecimal`
- `BigInteger`
- enums
- arrays
- `Collection`, `List`, `Set`
- `Map<String, ?>`
- `Optional`
- Java records
- POJOs with no-args constructor or `@YamlCreator`

## What Is Supported Today

The current parser/writer supports a safe, practical subset of YAML:

- single YAML document
- `%YAML 1.2` directive
- document start marker `---`
- document end marker `...`
- block mappings
- block sequences
- scalar mapping keys
- plain scalars
- single-quoted scalars
- double-quoted scalars with a small supported escape set
- comments
- UTF-8 text input
- alias nodes as parsed syntax objects

## What Is Intentionally Not Supported Yet

These are currently rejected or intentionally left disabled:

- multi-document streams
- flow collections like `[1, 2]` and `{a: 1}`
- merge keys
- anchor resolution during Java binding
- custom tags for object construction
- implicit polymorphic deserialization
- arbitrary non-scalar mapping keys in Java binding
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

Exclude a field or record component from binding:

```java
import io.pure.yaml.annotation.YamlIgnore;

final class Credentials {
    private String username;

    @YamlIgnore
    private String password;
}
```

### `@YamlCreator`

Choose a constructor for POJO creation:

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

Skip `null` values during serialization:

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
    .processingLimits(new YamlProcessingLimits(
        64,      // maxDepth
        64,      // maxAliases
        1,       // maxDocuments
        500_000  // maxCharacters
    ))
    .build();
```

### Processing limits

`YamlProcessingLimits` exists to reduce exposure to pathological input:

- `maxDepth`
- `maxAliases`
- `maxDocuments`
- `maxCharacters`

The defaults are conservative and suitable for many config-oriented use cases.

## Safety Model

This library intentionally defaults to safer behavior.

### Safe-by-default choices

- single-document parsing
- no alias resolution in Java binding
- no custom object construction from tags
- no code execution hooks
- configurable parser limits
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

The project uses dedicated exceptions:

- `YamlParseException` for syntax and parser-limit failures
- `YamlWriteException` for invalid YAML graph writing
- `YamlMappingException` for Java binding failures

Typical binding errors include:

- unknown properties
- unsupported collection/map targets
- invalid boolean lexemes for YAML 1.2 binding
- alias resolution attempts where disabled
- cyclic object graphs

## Testing

The project includes tests for:

- records and POJOs
- generic collections
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

## Limitations

Before using this library in production, be aware of the current scope:

- not a full YAML 1.2 implementation
- not yet a general-purpose replacement for mature YAML libraries
- no flow-style collection support
- no merge-key support
- no tag-driven object model
- no alias resolution for object binding
- no streaming read/write API for very large documents

## Roadmap

Planned directions include:

1. anchors and aliases as first-class graph resolution
2. optional multi-document stream support
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
