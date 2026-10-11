# Benchmark results

Environment: Apple M5 Pro, 48 GB RAM, macOS 26.6.2, Java "21.0.11" 2026-04-21 LTS, single thread.
Libraries: io.pure:yaml 1.3, SnakeYAML 2.7 (YAML 1.1), snakeyaml-engine 3.2 (YAML 1.2, shown as
`engine`), jackson-dataformat-yaml 2.22.3 (which parses with SnakeYAML).

Methodology:

- JMH 1.37, average time per operation, 3 forks with `-Xms4g -Xmx4g -XX:+AlwaysPreTouch`, allocation
  measured with `-prof gc` (`gc.alloc.rate.norm`). Warmup and measurement are 5 × 2 s; the 10 MB
  inputs use 5 × 3 s warmup and 5 × 5 s measurement, the real documents 5 × 3 s measurement.
- For JMH, all libraries are configured with raised size, depth and alias limits so that every input
  is accepted, and snakeyaml-engine uses the YAML 1.2 core schema (its default is the JSON schema).
- Before the run, `bench.Validate` checks that every library reads every real document with the same
  number of documents, and that io.pure and snakeyaml-engine produce equal maps and lists.
- Edge cases, cold start and written output use each library's default settings, each edge case in
  its own JVM (`-Xmx512m -Xss2m`, 30 s timeout). The only exception is `aliases_10000_enabled`, where
  alias resolution is enabled (io.pure) or the alias limit raised to 20 000 (SnakeYAML, engine).

Generated on 2026-10-11 by `report.py`, `bench.Sizes` and `bench.EdgeRunner` (see `run-all.sh`).

## Summary

| Inputs | vs SnakeYAML | vs snakeyaml-engine | vs Jackson |
|---|---|---|---|
| Real documents, parse to tree or maps | 2.0–4.2× | 2.3–4.7× | 1.4–3.3× |
| Synthetic inputs, parse | 1.4–5.7× | 1.8–6.5× | 1.4–3.9× |
| Bind to objects | 4.9–5.5× | n/a | 2.2× |
| Write objects | 9.8–16.8× | n/a | 2.1–2.3× |
| Allocation, except block scalars (about equal) | 2.4–10.8× less | 2.9–5.7× less | 1.4–3.6× less |

The lead is smallest on large multi-document manifests (cert-manager: 1.4–1.7× vs Jackson) and on
block scalars, where every library mostly copies text; it is largest on writing and on block-style
data (records, ingress-nginx, compose: 2.4–3.9× vs Jackson).

## Inputs

| Input | bytes | lines | documents | comment lines |
|---|---|---|---|---|
| small configuration | 1051 | 47 | 1 | 1 |
| 500 records | 104084 | 5001 | 1 | 0 |
| 50 000 records | 10807234 | 500001 | 1 | 0 |
| 10 000 records, single-line JSON | 1905905 | 1 | 1 | 0 |
| 10 000 aliases | 458965 | 20005 | 1 | 0 |
| 2 000 block scalars | 1565800 | 42000 | 1 | 0 |
| 1 000 documents | 44780 | 4000 | 1000 | 0 |
| openapi-github.yaml | 9904441 | 263536 | 1 | 23 |
| cert-manager.yaml | 1034400 | 13960 | 47 | 112 |
| helm-values.yaml | 219153 | 6218 | 1 | 3477 |
| faker-ru.yml | 44692 | 1401 | 1 | 0 |
| ingress-nginx.yaml | 16384 | 670 | 19 | 0 |
| compose.yaml | 1561 | 68 | 1 | 3 |

## Synthetic inputs: time per operation (lower is better; ± is the 99.9% confidence interval)

| Benchmark | Input | pure | snakeyaml | engine | jackson | pure MB/s | vs SnakeYAML | vs engine | vs Jackson |
|---|---|---|---|---|---|---|---|---|---|
| tree_small | 1 KB | 6.7 µs ± 1% | 26.9 µs ± 1% | 29.8 µs ± 2% | 21.6 µs ± 1% | 158 | 4.0× | 4.5× | 3.2× |
| tree_medium | 102 KB | 582.3 µs ± 1% | 2.93 ms ± 1% | 3.02 ms ± 2% | 2.29 ms ± 1% | 179 | 5.0× | 5.2× | 3.9× |
| tree_large | 10.3 MB | 64.86 ms ± 4% | 288.05 ms ± 1% | 321.01 ms ± 1% | 229.38 ms ± 1% | 167 | 4.4× | 4.9× | 3.5× |
| untyped_small | 1 KB | 8.8 µs ± 1% | 36.6 µs ± 3% | 42.9 µs ± 1% | 21.7 µs ± 1% | 120 | 4.2× | 4.9× | 2.5× |
| untyped_medium | 102 KB | 867.6 µs ± 1% | 4.74 ms ± 1% | 5.29 ms ± 0% | 2.23 ms ± 1% | 120 | 5.5× | 6.1× | 2.6× |
| untyped_large | 10.3 MB | 94.16 ms ± 1% | 536.92 ms ± 1% | 612.06 ms ± 3% | 224.87 ms ± 1% | 115 | 5.7× | 6.5× | 2.4× |
| json_untyped | 1.8 MB | 16.78 ms ± 1% | 77.03 ms ± 2% | 91.93 ms ± 2% | 41.83 ms ± 2% | 114 | 4.6× | 5.5× | 2.5× |
| anchors_untyped | 448 KB | 3.68 ms ± 1% | 17.35 ms ± 1% | 21.72 ms ± 1% | 6.67 ms ± 3% | 125 | 4.7× | 5.9× | 1.8× |
| blockScalars_tree | 1.5 MB | 2.78 ms ± 2% | 3.98 ms ± 2% | 4.90 ms ± 0% | 3.91 ms ± 1% | 562 | 1.4× | 1.8× | 1.4× |
| stream_1000 | 44 KB | 419.4 µs ± 0% | 2.11 ms ± 1% | 2.19 ms ± 0% | 1.17 ms ± 4% | 107 | 5.0× | 5.2× | 2.8× |
| bind_medium | 102 KB | 1.03 ms ± 1% | 5.01 ms ± 2% | n/a | 2.22 ms ± 2% | 101 | 4.9× | n/a | 2.2× |
| bind_large | 10.3 MB | 104.40 ms ± 1% | 571.00 ms ± 2% | n/a | 230.81 ms ± 1% | 104 | 5.5× | n/a | 2.2× |
| write_medium | – | 544.4 µs ± 4% | 5.35 ms ± 0% | n/a | 1.13 ms ± 0% | n/a | 9.8× | n/a | 2.1× |
| write_large | – | 50.75 ms ± 3% | 852.34 ms ± 3% | n/a | 117.88 ms ± 1% | n/a | 16.8× | n/a | 2.3× |

## Real documents: time per operation (lower is better)

| Document | Operation | pure | snakeyaml | engine | jackson | pure MB/s | vs SnakeYAML | vs engine | vs Jackson |
|---|---|---|---|---|---|---|---|---|---|
| openapi-github.yaml (9.4 MB) | tree | 49.51 ms ± 1% | 131.71 ms ± 1% | 141.10 ms ± 0% | 109.93 ms ± 4% | 200 | 2.7× | 2.8× | 2.2× |
| openapi-github.yaml (9.4 MB) | untyped | 68.29 ms ± 3% | 226.75 ms ± 1% | 272.19 ms ± 1% | 105.87 ms ± 0% | 145 | 3.3× | 4.0× | 1.6× |
| cert-manager.yaml (1010 KB) | tree | 3.37 ms ± 2% | 6.90 ms ± 1% | 7.74 ms ± 1% | 5.80 ms ± 1% | 307 | 2.0× | 2.3× | 1.7× |
| cert-manager.yaml (1010 KB) | untyped | 4.21 ms ± 3% | 9.14 ms ± 1% | 10.22 ms ± 0% | 5.84 ms ± 0% | 246 | 2.2× | 2.4× | 1.4× |
| helm-values.yaml (214 KB) | tree | 529.0 µs ± 0% | 1.25 ms ± 0% | 1.48 ms ± 1% | 1.11 ms ± 1% | 414 | 2.4× | 2.8× | 2.1× |
| helm-values.yaml (214 KB) | untyped | 610.1 µs ± 1% | 1.71 ms ± 1% | 2.04 ms ± 0% | 1.11 ms ± 1% | 359 | 2.8× | 3.3× | 1.8× |
| faker-ru.yml (44 KB) | tree | 171.8 µs ± 1% | 439.7 µs ± 5% | 429.9 µs ± 1% | 419.3 µs ± 2% | 260 | 2.6× | 2.5× | 2.4× |
| faker-ru.yml (44 KB) | untyped | 215.2 µs ± 1% | 558.7 µs ± 6% | 586.7 µs ± 4% | 429.3 µs ± 3% | 208 | 2.6× | 2.7× | 2.0× |
| ingress-nginx.yaml (16 KB) | tree | 75.4 µs ± 1% | 309.5 µs ± 1% | 345.9 µs ± 2% | 252.2 µs ± 0% | 217 | 4.1× | 4.6× | 3.3× |
| ingress-nginx.yaml (16 KB) | untyped | 101.8 µs ± 0% | 431.3 µs ± 0% | 479.2 µs ± 1% | 251.7 µs ± 0% | 161 | 4.2× | 4.7× | 2.5× |
| compose.yaml (2 KB) | tree | 7.5 µs ± 1% | 26.9 µs ± 0% | 29.6 µs ± 0% | 22.5 µs ± 1% | 207 | 3.6× | 3.9× | 3.0× |
| compose.yaml (2 KB) | untyped | 9.8 µs ± 1% | 37.8 µs ± 0% | 42.6 µs ± 1% | 23.9 µs ± 1% | 159 | 3.8× | 4.3× | 2.4× |

Notes:

- "tree" is each library's own document model: io.pure `YamlNode`, SnakeYAML and snakeyaml-engine
  `Node` (with positions and tags), Jackson `JsonNode`. Jackson builds the same `JsonNode` in both
  operations, so its tree and untyped times are equal; the other libraries build a YAML tree first and
  convert it into maps and lists.
- Jackson does not resolve aliases: in `anchors_untyped` it reads each alias as the anchor name (a
  string), which is less work than the others do.
- `write_*` is not the same output for every library (see "Written output" below): io.pure indents
  sequences and quotes every string, SnakeYAML uses flow style for leaf collections and adds a class
  tag, Jackson writes a `---` marker and does not indent sequences.

## Allocation per operation (lower is better)

| Benchmark | pure | snakeyaml | engine | jackson | vs SnakeYAML | vs engine | vs Jackson |
|---|---|---|---|---|---|---|---|
| tree_small | 26 KB | 115 KB | 140 KB | 88 KB | 4.5× | 5.4× | 3.4× |
| tree_medium | 2.8 MB | 12.0 MB | 13.7 MB | 8.3 MB | 4.3× | 4.9× | 2.9× |
| tree_large | 283.0 MB | 1180.7 MB | 1396.9 MB | 835.1 MB | 4.2× | 4.9× | 3.0× |
| untyped_small | 35 KB | 131 KB | 163 KB | 90 KB | 3.8× | 4.7× | 2.6× |
| untyped_medium | 3.7 MB | 13.6 MB | 15.1 MB | 8.1 MB | 3.7× | 4.1× | 2.2× |
| untyped_large | 368.5 MB | 1383.1 MB | 1528.6 MB | 811.4 MB | 3.8× | 4.1× | 2.2× |
| json_untyped | 65.9 MB | 245.8 MB | 272.8 MB | 180.9 MB | 3.7× | 4.1× | 2.7× |
| anchors_untyped | 17.3 MB | 47.5 MB | 58.7 MB | 24.5 MB | 2.7× | 3.4× | 1.4× |
| blockScalars_tree | 19.9 MB | 19.5 MB | 21.2 MB | 19.5 MB | 1.0× | 1.1× | 1.0× |
| stream_1000 | 2.9 MB | 7.9 MB | 9.4 MB | 4.7 MB | 2.7× | 3.3× | 1.6× |
| bind_medium | 3.5 MB | 13.3 MB | n/a | 7.7 MB | 3.8× | n/a | 2.2× |
| bind_large | 357.8 MB | 1347.3 MB | n/a | 776.9 MB | 3.8× | n/a | 2.2× |
| write_medium | 1.1 MB | 11.8 MB | n/a | 2.4 MB | 10.8× | n/a | 2.2× |
| write_large | 112.4 MB | 1168.2 MB | n/a | 246.7 MB | 10.4× | n/a | 2.2× |
| openapi-github.yaml tree | 134.3 MB | 500.9 MB | 605.3 MB | 370.9 MB | 3.7× | 4.5× | 2.8× |
| openapi-github.yaml untyped | 189.5 MB | 574.5 MB | 692.4 MB | 367.2 MB | 3.0× | 3.7× | 1.9× |
| cert-manager.yaml tree | 7.7 MB | 23.0 MB | 27.7 MB | 18.5 MB | 3.0× | 3.6× | 2.4× |
| cert-manager.yaml untyped | 10.6 MB | 25.6 MB | 30.3 MB | 18.4 MB | 2.4× | 2.9× | 1.7× |
| helm-values.yaml tree | 1.8 MB | 4.6 MB | 5.8 MB | 3.8 MB | 2.6× | 3.3× | 2.2× |
| helm-values.yaml untyped | 2.1 MB | 5.1 MB | 6.4 MB | 3.8 MB | 2.5× | 3.1× | 1.9× |
| faker-ru.yml tree | 641 KB | 2.0 MB | 2.4 MB | 1.8 MB | 3.3× | 3.8× | 2.9× |
| faker-ru.yml untyped | 774 KB | 2.1 MB | 2.5 MB | 1.8 MB | 2.8× | 3.3× | 2.4× |
| ingress-nginx.yaml tree | 286 KB | 1.2 MB | 1.6 MB | 950 KB | 4.3× | 5.7× | 3.3× |
| ingress-nginx.yaml untyped | 399 KB | 1.4 MB | 1.7 MB | 947 KB | 3.5× | 4.5× | 2.4× |
| compose.yaml tree | 25 KB | 113 KB | 140 KB | 89 KB | 4.5× | 5.6× | 3.6× |
| compose.yaml untyped | 34 KB | 130 KB | 162 KB | 93 KB | 3.8× | 4.7× | 2.7× |

## Edge cases (default settings, -Xmx512m, timeout 30 s)

| Case | pure | snakeyaml | engine | jackson |
|---|---|---|---|---|
| billion_laughs | rejected, 11 ms: YamlMappingException: Alias resolution is not enabled at $.a1[0]: *a0 (see YamlMapperBuilder.resolveAl... | rejected, 10 ms: YAMLException: Number of aliases for non-scalar nodes exceeds the specified max=50 | rejected, 16 ms: YamlEngineException: Number of aliases for non-scalar nodes exceeds the specified max=50 | accepted, 24 ms: map(10) {a0=[lol, lol, lol, lol, lol, lol, lol, lol, lol, lol], a1=[a0, a0, a0, a0, a0, ... |
| deep_flow_10000 | rejected, 9 ms: YamlParseException: YAML nesting depth exceeds configured limit | rejected, 19 ms: YAMLException: Nesting Depth exceeded max 50 | crashed, 120 ms: StackOverflowError | rejected, 73 ms: IllegalStateException: com.fasterxml.jackson.core.exc.StreamConstraintsException: Document nesting dept... |
| deep_block_1200 | rejected, 25 ms: YamlParseException: YAML nesting depth exceeds configured limit | rejected, 11 ms: YAMLException: Nesting Depth exceeded max 50 | accepted, 43 ms: map(1) {k={k={k={k={k={k={k={k={k={k={k={k={k={k={k={k={k={k={k={k={k={k={k={k={k={k={k... | rejected, 42 ms: IllegalStateException: com.fasterxml.jackson.core.exc.StreamConstraintsException: Document nesting dept... |
| json_single_line_20mb | rejected, 138 ms: YamlParseException: YAML input exceeds configured size limit | rejected, 305 ms: YAMLException: The incoming YAML document exceeds the limit: 3145728 code points. | rejected, 323 ms: YamlEngineException: The incoming YAML document exceeds the limit: 3145728 code points. | rejected, 244 ms: IllegalStateException: com.fasterxml.jackson.dataformat.yaml.JacksonYAMLParseException: The incoming YA... |
| block_document_20mb | rejected, 137 ms: YamlParseException: YAML input exceeds configured size limit | rejected, 334 ms: YAMLException: The incoming YAML document exceeds the limit: 3145728 code points. | rejected, 347 ms: YamlEngineException: The incoming YAML document exceeds the limit: 3145728 code points. | rejected, 261 ms: IllegalStateException: com.fasterxml.jackson.dataformat.yaml.JacksonYAMLParseException: The incoming YA... |
| plain_scalar_10mb | rejected, 9 ms: YamlParseException: YAML input exceeds configured size limit | rejected, 4184 ms: YAMLException: The incoming YAML document exceeds the limit: 3145728 code points. | rejected, 4429 ms: YamlEngineException: The incoming YAML document exceeds the limit: 3145728 code points. | rejected, 4200 ms: IllegalStateException: com.fasterxml.jackson.dataformat.yaml.JacksonYAMLParseException: The incoming YA... |
| huge_number_1e1000000000 | rejected, 8 ms: YamlMappingException: Number out of range at $.value: 1e1000000000 | accepted, 9 ms: map(1) {value=Infinity} | accepted, 13 ms: map(1) {value=Infinity} | accepted, 21 ms: map(1) {value=Infinity} |
| duplicate_keys | rejected, 9 ms: YamlParseException: Duplicate mapping key 'a' at line 3 | accepted, 23 ms: map(2) {b=2, a=3} | rejected, 17 ms: DuplicateKeyException: while constructing a mapping\n in reader, line 1, column 1:\n    a: 1\n    ^\nfo... | accepted, 22 ms: map(2) {a=3, b=2} |
| unknown_tag_object | accepted, 10 ms: list(1) depth 4, leaf http://127.0.0.1:1/ | rejected, 8 ms: ComposerException: Global tag is not allowed: tag:yaml.org,2002:javax.script.ScriptEngineManager\n ... | rejected, 17 ms: ConstructorException: could not determine a constructor for the tag tag:yaml.org,2002:javax.script.Scr... | accepted, 23 ms: list(1) depth 4, leaf http://127.0.0.1:1/ |
| utf16_bytes | accepted, 10 ms: map(2) {name=Ann, age=30} | accepted, 9 ms: map(2) {name=Ann, age=30} | accepted, 14 ms: map(2) {name=Ann, age=30} | rejected, 10 ms: IllegalStateException: com.fasterxml.jackson.dataformat.yaml.JacksonYAMLParseException: java.io.CharCon... |
| aliases_10000 | rejected, 39 ms: YamlMappingException: Alias resolution is not enabled at $.services[0].settings: *defaults (see YamlMa... | rejected, 14 ms: YAMLException: Number of aliases for non-scalar nodes exceeds the specified max=50 | rejected, 20 ms: YamlEngineException: Number of aliases for non-scalar nodes exceeds the specified max=50 | accepted, 72 ms: map(2) {defaults={timeout=30, retries=3, tags=[a, b, c]}, services=[{name=service0, set... |
| aliases_10000_enabled | accepted, 62 ms: map(2) {defaults={timeout=30, retries=3, tags=[a, b, c]}, services=[{name=service0, set... | accepted, 89 ms: map(2) {defaults={timeout=30, retries=3, tags=[a, b, c]}, services=[{name=service0, set... | accepted, 101 ms: map(2) {defaults={timeout=30, retries=3, tags=[a, b, c]}, services=[{name=service0, set... | accepted, 72 ms: map(2) {defaults={timeout=30, retries=3, tags=[a, b, c]}, services=[{name=service0, set... |
| invalid_tab_indent | rejected, 8 ms: YamlParseException: Tabs are not supported for indentation at line 2 | rejected, 8 ms: ScannerException: while scanning for the next token\nfound character '\t(TAB)' that cannot start a... | rejected, 17 ms: ScannerException: while scanning for the next token\nfound character '\t(TAB)' that cannot start a... | rejected, 22 ms: IllegalStateException: com.fasterxml.jackson.dataformat.yaml.snakeyaml.error.MarkedYAMLException: while... |
| empty_document | accepted, 1 ms: null | accepted, 4 ms: null | accepted, 7 ms: null | rejected, 10 ms: IllegalStateException: com.fasterxml.jackson.databind.exc.MismatchedInputException: No content to map d... |

## Cold start (JVM start to first parsed document, median of 7 runs, ms)

| Library | default JIT | -XX:TieredStopAtLevel=1 |
|---|---|---|
| pure | 20 | 19 |
| snakeyaml | 34 | 33 |
| engine | 30 | 28 |
| jackson | 90 | 84 |

## Written output (default settings)

Size of `Records` with 500 and 50 000 records written by each library.

| Library | 500 records, bytes | 500 records, lines | 50 000 records, bytes |
|---|---|---|---|
| pure | 120083 | 6501 | 12407233 |
| snakeyaml | 89106 | 3502 | 9307256 |
| jackson | 104088 | 6502 | 10807238 |

Notes on the edge cases:

- `billion_laughs` and `aliases_10000`: with default settings io.pure does not resolve aliases at all
  (alias resolution is opt-in) and SnakeYAML and snakeyaml-engine allow 50 aliases of collections, so
  all three reject both inputs. Jackson "accepts" both because it does not resolve aliases: every
  alias is read as the anchor name (`"a0"`, `"defaults"`), so the result is silently wrong.
- `aliases_10000_enabled`: with alias resolution enabled, io.pure, SnakeYAML and snakeyaml-engine
  resolve the 10 000 aliases; io.pure still rejects `billion_laughs` because its expansion exceeds
  `maxAliasExpansion`.
- `deep_flow_10000` and `deep_block_1200`: snakeyaml-engine has no nesting depth limit; it crashes
  with `StackOverflowError` on 10 000 nested flow sequences and accepts 1 200 nested block mappings.
- `huge_number_1e1000000000`: SnakeYAML, snakeyaml-engine and Jackson silently produce `Infinity`.
- `duplicate_keys`: SnakeYAML and Jackson keep the last value; io.pure and snakeyaml-engine reject the
  document.
- `unknown_tag_object`: no library constructs the object; SnakeYAML and snakeyaml-engine reject the
  global tag, io.pure and Jackson keep the data and ignore the tag.
- `plain_scalar_10mb`: all libraries reject the input by their size limit, but SnakeYAML,
  snakeyaml-engine and Jackson take more than 4 s to do so; io.pure takes 9 ms.
- The times of the edge cases include class loading of a fresh JVM, so they are not throughput
  figures.

## Footprint (runtime classpath)

| Library | Jars | Size | Classes |
|---|---|---|---|
| io.pure:yaml 1.3 | 1 | 117 KB | 57 |
| SnakeYAML 2.7 | 1 | 324 KB | 233 |
| snakeyaml-engine 3.2 | 1 | 302 KB | 225 |
| jackson-dataformat-yaml 2.22.3 | 5 (dataformat-yaml, databind, core, annotations, snakeyaml) | 2 716 KB | 1 370 |
