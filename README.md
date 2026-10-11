# yaml

YAML parsing, writing and Java object binding for Java 21, with a deliberately safe, RFC 9512-oriented
design and no runtime dependencies.

This project focuses on:

- mapping Java records and POJOs to and from YAML
- parsing YAML 1.2 (block and flow style, JSON included) into a tree that keeps anchors, tags and styles
- writing readable YAML back from that tree or from Java objects
- safe defaults for `application/yaml`: limits on every resource, no object construction from tags,
  and errors instead of silently wrong results
- a small footprint: one 117 KB jar

It fits configuration files, services that exchange YAML or JSON, and tooling that needs predictable,
bounded behavior on untrusted input. The project is young: the API may still change between minor
versions, so pin the version you use.

## Why This Project Exists

YAML is convenient, but full YAML support is also complicated:

- the format allows graphs, not just trees
- tag handling can become unsafe
- alias traversal and recursive structures can trigger resource-exhaustion problems
- interoperability across implementations is often weaker than people expect

RFC 9512 does not redefine the whole YAML language. Instead, it registers the `application/yaml` media
type and highlights interoperability and security concerns around YAML processing.

This project takes that seriously:

- single-document input by default (multi-document streams only through `parseStream` with an explicit limit)
- no implicit object construction
- tags never construct objects: core schema tags (`!!str`, `!!int`, ...) are hints for value resolution,
  other tags are kept as metadata; unsupported syntax is rejected instead of being read as text
- duplicate mapping keys are rejected
- aliases must refer to an anchor defined earlier in the same document; recursive aliases are rejected
- alias resolution in Java binding is opt-in and bounded by expansion and depth limits

## Features

### Current capabilities

- JDK 21, Maven, no runtime dependencies
- YAML 1.2 parser: block and flow collections (JSON documents included), multi-line and block scalars,
  anchors and aliases, tags and `%TAG` directives, explicit and empty keys, multi-document streams
- input as text or as bytes in UTF-8, UTF-16 or UTF-32, with the encoding detected automatically
- YAML tree model (`YamlNode`) that keeps anchors, tags and scalar styles
- YAML writer: block style, literal block scalars for multi-line text, anchors, aliases and tags,
  documents and streams with their markers and `%YAML` directives
- Java object mapper for records and POJOs, including generic beans via `YamlTypeRef`
- untyped binding to `Object`, `Map<String, Object>` and `List<Object>` using the YAML 1.2 core schema
- common JDK value types (`UUID`, `URI`, `Path`, `java.time` types, `Locale`, ...)
- custom adapters via `TypeAdapter`
- annotations for naming, ignoring, creators, inclusion, and projected scalar values
- fragment identifiers of RFC 9512 (`*anchor` and JSON Pointer) through `YamlFragment`
- configurable processing limits for size, depth, documents, aliases, alias expansion and numbers
- cycle detection during object serialization and YAML graph writing

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

## Performance

Compared with SnakeYAML 2.7 and Jackson 2.22.3 (`jackson-dataformat-yaml`) on the same inputs, single
thread, JDK 21 (lower is better):

| Operation | io.pure:yaml | SnakeYAML | Jackson |
| --- | --- | --- | --- |
| Parse 10 MB to a YAML tree | **60 ms** | 294 ms | 233 ms |
| Read 10 MB into maps and lists | **87 ms** | 545 ms | 233 ms |
| Bind 10 MB to POJOs | **98 ms** | 578 ms | 229 ms |
| Write 50 000 objects | **52 ms** | 884 ms | 118 ms |
| Read 1.8 MB of single-line JSON | **17 ms** | 76 ms | 41 ms |
| Resolve 10 000 aliases | **4.4 ms** | 18 ms | 6.6 ms (aliases not resolved) |
| Memory allocated reading 10 MB into maps | **369 MB** | 1 367 MB | 811 MB |
| JVM start to first parsed document | **19 ms** | 32 ms | 84 ms |
| Runtime footprint | **117 KB, 1 jar** | 324 KB, 1 jar | 2.7 MB, 5 jars |

Every benchmark of the suite is faster than both libraries. Allocation is 1.4–11× lower in all of
them except block scalars, where all three libraries allocate about the same (the text itself). Edge
cases run with each library's defaults: io.pure:yaml also rejects inputs that the others accept
silently with a wrong result (duplicate keys, `1e1000000000` read as `Infinity`, aliases left
unresolved by Jackson), and it rejects an oversized 10 MB scalar in 9 ms where the others take more
than 4 s.

All measurements, allocation figures, edge cases and the test environment are in
[BENCHMARKS.md](BENCHMARKS.md); the benchmark code and the instructions to reproduce them are in
[benchmarks/](benchmarks/README.md). Results depend on the hardware and JVM, so measure on your own
workload before relying on them.

## What Is Supported Today

The parser and writer support:

- single YAML document (`Yaml.parse`), or several documents through `Yaml.parseStream` within `maxDocuments`
- `%YAML 1.x` and `%TAG` directives
- document start marker `---`, including a scalar on the same line (`--- value`), and document end marker `...`
- block mappings and block sequences with any consistent indentation
- compact sequences under a mapping key (`key:` followed by `- item` at the same indentation)
- nested sequence entries on one line (`- - item`, `- key: value`)
- flow collections (`[a, b]`, `{a: 1}`), nested and spanning several lines, including single-pair
  entries (`[key: value]`), empty values (`{a, b: }`), trailing commas and JSON-style adjacent values
  (`{"a":1}`); JSON documents can be read as they are
- plain, single-quoted and double-quoted scalars, multi-line with YAML line folding (a single line
  break becomes a space, each empty line becomes a line feed, `\` escapes a line break)
- double-quoted scalars with the full YAML escape set (`\0`, `\n`, `\t`, `\xXX`, `\uXXXX`, `\UXXXXXXXX`, ...)
- literal (`|`) and folded (`>`) block scalars with chomping (`-`, `+`) and indentation indicators
- anchors (`&name`) on scalars, collections and keys, and aliases (`*name`), including alias keys
  whose anchor is a scalar
- tags: shorthands (`!local`, `!!str`, `!e!name` declared by `%TAG`), verbatim tags (`!<tag:...>`) and the
  non-specific tag `!`, on any node; `%TAG` directives are scoped to their document
- scalar mapping keys, including explicit entries (`? key` / `: value`, also in flow collections and
  with block scalar or multi-line keys) and empty keys (`: value`)
- implicit keys of up to 1024 characters, as YAML requires (the writer uses `? key` for longer keys)
- empty values (`key:` or `-`), read as null
- streams without documents (empty input, only comments or `...`): `parseStream` returns no documents,
  `parse` returns a document with an empty (null) root
- comments
- tabs as separators after indentation (for example `key:\tvalue` or a tab before a continuation line);
  tabs are never accepted as indentation
- text, or bytes in UTF-8, UTF-16 or UTF-32 with the encoding detected from the byte order mark or
  the first character (`Yaml.parse(byte[])`, `Yaml.parseStream(byte[] | InputStream, limits)`,
  `YamlMapper.readValue(byte[] | InputStream, ...)`); a byte order mark is accepted only at the start of
  a document, and characters outside the YAML printable set are rejected

Input that falls outside this subset is rejected with a `YamlParseException` that names the line.
The parser never silently skips or reinterprets content. On the official
[yaml-test-suite](#yaml-test-suite), 292 of 308 valid documents are parsed with the expected result.

## What Is Not Supported

Not supported by design, because it conflicts with the safety model:

- object construction from tags: local and unknown tags are kept only as metadata, and the target
  Java type alone decides how a node is bound; there is no implicit polymorphic deserialization
- recursive aliases (an alias inside the node its anchor belongs to)
- duplicate keys, including two empty keys in one mapping
- multi-document streams in `parse()` (use `parseStream()` with an explicit `maxDocuments`)

Not supported yet (see [Roadmap](#roadmap)):

- non-scalar mapping keys such as `[a, b]: value` or `? {a: 1}`
- merge keys (`<<`)
- block collections on the document start line (`--- key: value`)
- map keys of Java types other than those listed in [Supported Java binding types](#supported-java-binding-types)
- reading types that use `@YamlValue` back from their scalar form

## Maven Coordinates

```xml
<dependency>
    <groupId>io.pure</groupId>
    <artifactId>yaml</artifactId>
    <version>1.3</version>
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

Run the tests:

```bash
mvn test
```

Build the JAR:

```bash
mvn package
```

Run the tests together with the build checks used in CI (see [Testing](#testing)):

```bash
mvn verify
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

Strings are written double-quoted, so that values such as `"true"` or `"123"` stay strings; multi-line
strings are written as literal block scalars (`|`). `writeValueAsBytes` returns the same text as UTF-8.

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

`readValue` also accepts a `byte[]` or an `InputStream` in UTF-8, UTF-16 or UTF-32.

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

Scalars keep their text, style (`YamlScalar.style()`), tag and anchor; a mapper turns them into Java values.

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

The writer uses block style. Multi-line text is written as a literal block scalar (`|`) with the
chomping and indentation indicators its value needs, and falls back to a double-quoted scalar when a
literal block cannot hold the value (for example a carriage return). Empty nodes are written as empty
values (`key:`, `-`); anchors, aliases and tags are written back. Keys longer than 1024 characters are
written as explicit entries (`? key` / `: value`), because YAML limits implicit keys to that length.

Documents and streams keep their markers and directives:

```java
import io.pure.yaml.core.Yaml;
import io.pure.yaml.core.model.YamlDocument;
import io.pure.yaml.core.model.YamlStream;
import io.pure.yaml.core.parser.YamlProcessingLimits;

YamlProcessingLimits limits = new YamlProcessingLimits(64, 1_000, 10, 1_000_000); // up to 10 documents
YamlStream stream = Yaml.parseStream(text, limits);
String yaml = Yaml.stringify(stream);          // every document after "---"
String single = Yaml.stringify(new YamlDocument(root, "%YAML 1.2"));
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

`@YamlValue` applies to writing only: reading `"active"` back into `AsValue` fails with
`Expected mapping`. Register a `TypeAdapter` when the type must be read back from its scalar form.

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
        10_000,  // maxAliases
        1,       // maxDocuments
        500_000, // maxCharacters
        1_000,   // maxNumberLength
        10_000,  // maxAliasExpansion
        10       // aliasExpansionRatio
    ))
    .resolveAliases(true)
    .build();
```

| Option | Default | Meaning |
| --- | --- | --- |
| `writeNulls` | `true` | write properties whose value is `null` |
| `failOnUnknownProperties` | `true` | reject keys that match no property of the target type |
| `defaultInclusion` | `ALWAYS` | inclusion rule for properties without `@YamlInclude` |
| `coerceQuotedScalars` | `true` | allow quoted values such as `"8080"` or `"true"` in numeric and boolean targets; disable it to require unquoted values, as the YAML 1.2 core schema does |
| `resolveAliases` | `false` | read aliases as the node of their anchor (see [Anchors and aliases](#anchors-and-aliases)); when disabled, an alias in Java binding is an error |
| `processingLimits` | `YamlProcessingLimits.defaults()` | see [Processing limits](#processing-limits) |

### Tags

Tags of the YAML 1.2 core schema guide how a scalar is read: `!!str 123` and `! 123` are the text
`"123"`, `!!int "0x1F"` is 31, `!!float 1` is `1.0`, `!!bool "true"` is `true`, and `!!null ""` is null; a
value that contradicts its tag (such as `!!int abc`, or `!!map` on a sequence) is an error. Local and
unknown tags (`!thing`, `!<tag:example.com,2000:x>`) are kept in the tree (`YamlNode.tag()`) and written
back, but never select a Java type: the target type alone decides how a node is bound.

### Anchors and aliases

The parser links every alias to the node its anchor belongs to (`YamlAlias.target()`); an alias to an
undefined anchor, or to a node that is still being parsed, is a syntax error. The YAML tree keeps
aliases as `YamlAlias` nodes, so it never becomes cyclic, and the writer writes anchors and aliases back.

Java binding rejects aliases unless `resolveAliases(true)` is set. When it is enabled, an anchored node
is read once per `readValue` call and **all its aliases share that value**, as in SnakeYAML: changing
the object read for `*defaults` changes it everywhere it is referenced. Reading an alias therefore costs
no more than reading a reference, however large the anchored node is.

The result can still be exponentially large for whoever traverses it (the "billion laughs" input), so
the logical size and depth that aliases add are limited per call:

- aliases may add `max(maxAliasExpansion, aliasExpansionRatio × nodes read so far)` nodes: 10 000
  nodes, or 10 per node of the document, by default. A configuration that references one block
  thousands of times is accepted; exponential growth is rejected at once;
- the depth through aliases may not exceed `maxDepth`.

Set `aliasExpansionRatio` to `0` to make `maxAliasExpansion` an absolute limit.

```java
import io.pure.yaml.binding.mapper.YamlMapper;
import io.pure.yaml.binding.mapper.YamlTypeRef;

import java.util.List;

record Settings(int timeout, int retries) {
}

record Service(String name, Settings settings) {
}

YamlMapper mapper = YamlMapper.builder().resolveAliases(true).build();

List<Service> services = mapper.readValue("""
    - name: api
      settings: &defaults {timeout: 30, retries: 3}
    - name: worker
      settings: *defaults
    """, new YamlTypeRef<List<Service>>() {});
```

### Fragment identifiers

`YamlFragment` evaluates the fragment identifiers that RFC 9512 defines for `application/yaml`:

```java
import io.pure.yaml.core.Yaml;
import io.pure.yaml.core.YamlFragment;
import io.pure.yaml.core.model.YamlNode;

YamlNode root = Yaml.parse(yaml);

YamlFragment.resolve(root, "#/servers/0/host"); // JSON Pointer (RFC 6901), follows aliases
YamlFragment.resolve(root, "#*defaults");       // the first node with anchor "defaults"
```

A fragment that matches nothing yields `Optional.empty()`. For a `YamlStream`, alias fragments search
all documents in order, and JSON Pointer fragments require a single-document stream.

### Processing limits

`YamlProcessingLimits` exists to reduce exposure to pathological input:

- `maxDepth` (default 128), also applied through aliases
- `maxAliases` (default 100 000): number of alias nodes in a stream; an alias costs as little as a
  scalar to parse, the expansion limits below are what protects the reader
- `maxDocuments` (default 1)
- `maxCharacters` (default 1 000 000); byte input is checked before it is decoded
- `maxNumberLength` (default 1 000): maximum length of a numeric literal and maximum magnitude (in
  digits) of `BigInteger`/`BigDecimal` values, which protects against inputs such as `1e1000000000`
- `maxAliasExpansion` (default 10 000) and `aliasExpansionRatio` (default 10): nodes that aliases may
  add to one `readValue` result, see [Anchors and aliases](#anchors-and-aliases)

The defaults are conservative and suitable for many config-oriented use cases. The parser can also
be used with custom limits directly: `Yaml.parse(text | bytes, limits)`,
`Yaml.parseStream(text | bytes | InputStream, limits)`.

## Safety Model

This library intentionally defaults to safer behavior.

### Safe-by-default choices

- single-document parsing
- no alias resolution in Java binding unless enabled; when enabled, expansion and depth are bounded
- recursive aliases and aliases to undefined anchors are rejected by the parser
- no object construction from tags
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

In practice, this project uses RFC 9512 as the guiding safety and interoperability baseline:

- fragment identifiers (section 1.2): `*anchor` and JSON Pointer fragments through `YamlFragment`
- multi-document streams (section 3.2): `parse()` signals an error instead of ignoring extra
  documents, and anchors are scoped to their document
- encodings: UTF-8, UTF-16 and UTF-32 input is detected and decoded; output bytes are UTF-8
- JSON interoperability (section 3.4): flow collections make JSON documents readable; keys are
  scalars, tags do not construct objects, and cyclic references are not supported
- resource exhaustion (section 4.2): parser limits, alias expansion and depth limits, no cycles

Reference:

- [RFC 9512 - The `application/yaml` Media Type](https://www.rfc-editor.org/rfc/rfc9512)

## Architecture

The code is split into three main areas.

### `io.pure.yaml.core`

Low-level YAML functionality:

- `Yaml`: entry point for parsing and writing
- `YamlException`: base class of all exceptions of the library
- `YamlFragment`: RFC 9512 fragment identifiers
- `parser`: `YamlParser`, `YamlProcessingLimits`, `YamlEncoding` (encoding detection), `YamlParseException`
- `writer`: `YamlWriter`, `YamlWriteException`
- `model`: `YamlNode` and its kinds `YamlScalar`, `YamlSequence`, `YamlMapping` (with `YamlEntry`) and
  `YamlAlias`; `YamlDocument`, `YamlStream`, `YamlScalarStyle` and the core schema tags in `YamlTags`

### `io.pure.yaml.binding`

Java object mapping:

- `YamlMapper`, `YamlMapperBuilder`, `YamlMapperConfig`
- `YamlTypeRef` for generic target types
- `TypeAdapter` for custom mappings
- `YamlMappingException`
- reflection-based introspection for records and POJOs

### `io.pure.yaml.annotation`

Binding annotations:

- `@YamlProperty`
- `@YamlIgnore`
- `@YamlCreator`
- `@YamlValue`
- `@YamlInclude`

The separate [benchmarks](benchmarks/README.md) module compares the library with SnakeYAML and Jackson;
it is not part of the library build.

## Error Handling

All exceptions extend `io.pure.yaml.core.YamlException`:

- `YamlParseException` for syntax and parser-limit failures, with the line number
- `YamlWriteException` for invalid YAML graph writing
- `YamlMappingException` for Java binding failures, with the path of the node (such as `$.services[1].settings`)

Typical binding errors include:

- unknown properties
- unsupported collection/map targets
- invalid boolean lexemes for YAML 1.2 binding
- invalid, out-of-range or oversized numbers
- values that contradict their core schema tag
- invalid values for JDK value types (dates, UUIDs, ...)
- types that are not accessible through reflection
- empty mapping keys, which cannot be bound to Java maps or properties
- alias resolution attempts where disabled, or alias expansion beyond the configured limits
- cyclic object graphs

## Testing

The project includes tests for:

- records and POJOs, generic collections and generic beans
- write/read round trips of nested collections, empty collections and special strings
- randomized round trips of YAML trees and fuzzing of the parser
- rejection of unsupported or malformed syntax
- quoted, multi-line and block scalars and escapes
- flow collections and JSON documents
- anchors, aliases, shared alias values and alias expansion limits
- tags, `%TAG` directives, explicit and empty keys
- YAML directives, document markers and streams
- encodings (UTF-8, UTF-16, UTF-32, byte order marks) and input size limits
- writer output: literal blocks, empty nodes, tags, long keys, documents and streams
- RFC 9512 fragment identifiers
- parser limits, cycle rejection and custom adapters
- strict boolean/null handling, the core schema scalar classifier and integers at type boundaries

Run the tests:

```bash
mvn test
```

`mvn verify` additionally runs the build checks used in CI:

- Maven Enforcer: JDK 21+, Maven 3.9+ and pinned plugin versions
- JaCoCo: coverage report in `target/site/jacoco` and minimum line/branch coverage
- SpotBugs: static analysis, with justified exclusions in `spotbugs-exclude.xml`

### YAML test suite

`YamlTestSuiteTest` runs the official [yaml-test-suite](https://github.com/yaml/yaml-test-suite)
when its `data` branch is available locally (CI pins the commit in `.github/workflows/build.yml`):

```bash
git clone --branch data https://github.com/yaml/yaml-test-suite.git /tmp/yaml-test-suite
YAML_TEST_SUITE_DIR=/tmp/yaml-test-suite mvn verify
```

The test reads each case as bytes and fails when invalid YAML is accepted, when the parsed
representation differs from the expected events (anchors, tags, scalar styles and values) or from
the expected JSON, or when valid YAML is rejected without being listed in
`src/test/resources/yaml-test-suite/unsupported-cases.txt`. That file lists the cases that use
syntax outside the supported subset; a case must be removed from it once it starts to pass.
Every parsed stream is also written with `Yaml.stringify` and read back, and must yield the same
events apart from scalar styles.

Current results: 292 of 308 valid documents are parsed and match the expected events and JSON; the
remaining 16 use non-scalar mapping keys (15) or two empty keys in one mapping (1); all 94 invalid
documents are rejected. All 292 parsed streams survive the writer round trip.

## Limitations

Before using this library in production, be aware of the current scope:

- YAML 1.2 syntax is covered except non-scalar mapping keys and block collections on the document start
  line; merge keys, a YAML 1.1 convention, are not supported (see [What Is Not Supported](#what-is-not-supported))
- no tag-driven object model (by design)
- alias resolution for object binding is opt-in
- the writer always uses block style; folded scalars are written as literal blocks
- documents are read into memory: there is no streaming read/write API for very large documents
- a young project: the API may still change between minor versions

## Roadmap

Planned directions include:

1. non-scalar mapping keys
2. merge-key policy support
3. failsafe and JSON schemas as alternatives to the core schema
4. reading `@YamlValue` types back from their scalar form
5. a streaming API and further performance tuning for large inputs
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

The project is functional, tested against the official YAML test suite, and still evolving:

- the supported YAML is listed above, and everything else is rejected with an explicit error
- the safety model is deliberately conservative and will stay the default
- the API may still change between minor versions

## License

This project is licensed under the Apache License 2.0.

See the [LICENSE](LICENSE) file for the full license text.
