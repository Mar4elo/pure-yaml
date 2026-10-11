# Benchmark results

Environment: Apple M5 Pro, 48 GB RAM, macOS 26.6.2, "21.0.11" 2026-04-21 LTS, single thread.
Libraries: io.pure:yaml 1.3, SnakeYAML 2.7, jackson-dataformat-yaml 2.22.3 (which parses with SnakeYAML).
JMH 1.37: 1 fork, 3 warmup and 5 measurement iterations of 1 s, average time per operation; all three
libraries configured with raised size, depth and alias limits so that every input is accepted.
Edge cases and cold start use each library's default settings in a separate JVM (-Xmx512m, 30 s timeout).
Generated on 2026-10-11 by `report.py`, `bench.EdgeRunner` and `bench.ColdStart`.

## Time per operation (lower is better; ± is the 99.9% confidence interval)

| Benchmark | Input | pure | snakeyaml | jackson | vs SnakeYAML | vs Jackson |
|---|---|---|---|---|---|---|
| tree_small | 1 KB | 6.6 µs ± 2% (159 MB/s) | 28.2 µs ± 2% (37 MB/s) | 21.4 µs ± 1% (49 MB/s) | 4.3× | 3.2× |
| tree_medium | 102 KB | 608.9 µs ± 1% (171 MB/s) | 2.86 ms ± 1% (36 MB/s) | 2.27 ms ± 2% (46 MB/s) | 4.7× | 3.7× |
| tree_large | 10.3 MB | 59.93 ms ± 2% (180 MB/s) | 294.17 ms ± 2% (37 MB/s) | 232.68 ms ± 1% (46 MB/s) | 4.9× | 3.9× |
| untyped_small | 1 KB | 8.7 µs ± 2% (120 MB/s) | 38.0 µs ± 0% (28 MB/s) | 21.5 µs ± 1% (49 MB/s) | 4.3× | 2.5× |
| untyped_medium | 102 KB | 865.4 µs ± 2% (120 MB/s) | 4.75 ms ± 3% (22 MB/s) | 2.28 ms ± 2% (46 MB/s) | 5.5× | 2.6× |
| untyped_large | 10.3 MB | 87.44 ms ± 4% (124 MB/s) | 545.15 ms ± 10% (20 MB/s) | 232.82 ms ± 1% (46 MB/s) | 6.2× | 2.7× |
| bind_medium | 102 KB | 1.02 ms ± 2% (102 MB/s) | 4.97 ms ± 1% (21 MB/s) | 2.23 ms ± 2% (47 MB/s) | 4.9× | 2.2× |
| bind_large | 10.3 MB | 97.62 ms ± 3% (111 MB/s) | 577.60 ms ± 13% (19 MB/s) | 229.41 ms ± 2% (47 MB/s) | 5.9× | 2.3× |
| write_medium | – | 526.2 µs ± 1% | 5.43 ms ± 2% | 1.14 ms ± 2% | 10.3× | 2.2× |
| write_large | – | 52.38 ms ± 2% | 883.86 ms ± 11% | 118.22 ms ± 1% | 16.9× | 2.3× |
| json_untyped | 1.8 MB | 16.50 ms ± 2% (116 MB/s) | 75.89 ms ± 4% (25 MB/s) | 41.23 ms ± 1% (46 MB/s) | 4.6× | 2.5× |
| anchors_untyped | 448 KB | 4.39 ms ± 3% (104 MB/s) | 17.62 ms ± 2% (26 MB/s) | 6.64 ms ± 3% (69 MB/s) | 4.0× | 1.5× |
| blockScalars_tree | 1.5 MB | 2.77 ms ± 2% (565 MB/s) | 3.95 ms ± 2% (396 MB/s) | 4.01 ms ± 4% (390 MB/s) | 1.4× | 1.4× |
| stream_1000 | 44 KB | 416.6 µs ± 2% (107 MB/s) | 2.20 ms ± 1% (20 MB/s) | 1.19 ms ± 2% (38 MB/s) | 5.3× | 2.9× |

## Allocation per operation (lower is better)

| Benchmark | pure | snakeyaml | jackson | vs SnakeYAML | vs Jackson |
|---|---|---|---|---|---|
| tree_small | 26 KB | 118 KB | 89 KB | 4.6× | 3.5× |
| tree_medium | 2.8 MB | 11.8 MB | 8.1 MB | 4.2× | 2.9× |
| tree_large | 283.0 MB | 1180.7 MB | 815.3 MB | 4.2× | 2.9× |
| untyped_small | 34 KB | 131 KB | 89 KB | 3.8× | 2.6× |
| untyped_medium | 3.7 MB | 13.6 MB | 8.1 MB | 3.7× | 2.2× |
| untyped_large | 368.5 MB | 1367.1 MB | 811.4 MB | 3.7× | 2.2× |
| bind_medium | 3.5 MB | 13.3 MB | 7.7 MB | 3.8× | 2.2× |
| bind_large | 357.8 MB | 1338.8 MB | 777.1 MB | 3.7× | 2.2× |
| write_medium | 1.1 MB | 11.8 MB | 2.4 MB | 10.8× | 2.2× |
| write_large | 112.0 MB | 1171.4 MB | 246.7 MB | 10.5× | 2.2× |
| json_untyped | 65.9 MB | 237.9 MB | 174.1 MB | 3.6× | 2.6× |
| anchors_untyped | 16.9 MB | 49.3 MB | 23.9 MB | 2.9× | 1.4× |
| blockScalars_tree | 19.9 MB | 19.8 MB | 19.5 MB | 1.0× | 1.0× |
| stream_1000 | 2.9 MB | 8.0 MB | 4.7 MB | 2.8× | 1.6× |

## Edge cases (default settings, -Xmx512m, timeout 30 s)

| Case | pure | snakeyaml | jackson |
|---|---|---|---|
| billion_laughs | error, 12 ms: YamlMappingException: Alias expansion exceeds configured limit at $.a3[7] | error, 9 ms: YAMLException: Number of aliases for non-scalar nodes exceeds the specified max=50 | accepted, 23 ms: map(10) {a0=[lol, lol, lol, lol, lol, lol, lol, lol, lol, lol], a1=[a0, a0, a0, a0, a0, ... |
| deep_flow_10000 | error, 10 ms: YamlParseException: YAML nesting depth exceeds configured limit | error, 19 ms: YAMLException: Nesting Depth exceeded max 50 | error, 72 ms: IllegalStateException: com.fasterxml.jackson.core.exc.StreamConstraintsException: Document nesting dept... |
| deep_block_1200 | error, 23 ms: YamlParseException: YAML nesting depth exceeds configured limit | error, 11 ms: YAMLException: Nesting Depth exceeded max 50 | error, 46 ms: IllegalStateException: com.fasterxml.jackson.core.exc.StreamConstraintsException: Document nesting dept... |
| json_single_line_20mb | error, 136 ms: YamlParseException: YAML input exceeds configured size limit | error, 301 ms: YAMLException: The incoming YAML document exceeds the limit: 3145728 code points. | error, 243 ms: IllegalStateException: com.fasterxml.jackson.dataformat.yaml.JacksonYAMLParseException: The incoming YA... |
| block_document_20mb | error, 135 ms: YamlParseException: YAML input exceeds configured size limit | error, 352 ms: YAMLException: The incoming YAML document exceeds the limit: 3145728 code points. | error, 259 ms: IllegalStateException: com.fasterxml.jackson.dataformat.yaml.JacksonYAMLParseException: The incoming YA... |
| plain_scalar_10mb | error, 9 ms: YamlParseException: YAML input exceeds configured size limit | error, 4181 ms: YAMLException: The incoming YAML document exceeds the limit: 3145728 code points. | error, 4212 ms: IllegalStateException: com.fasterxml.jackson.dataformat.yaml.JacksonYAMLParseException: The incoming YA... |
| huge_number_1e1000000000 | error, 8 ms: YamlMappingException: Number out of range at $.value: 1e1000000000 | accepted, 9 ms: map(1) {value=Infinity} | accepted, 23 ms: map(1) {value=Infinity} |
| duplicate_keys | error, 9 ms: YamlParseException: Duplicate mapping key 'a' at line 3 | accepted, 24 ms: map(2) {b=2, a=3} | accepted, 22 ms: map(2) {a=3, b=2} |
| unknown_tag_object | accepted, 10 ms: list(1) depth 4, leaf http://127.0.0.1:1/ | error, 8 ms: ComposerException: Global tag is not allowed: tag:yaml.org,2002:javax.script.ScriptEngineManager\n ... | accepted, 22 ms: list(1) depth 4, leaf http://127.0.0.1:1/ |
| utf16_bytes | accepted, 11 ms: map(2) {name=Ann, age=30} | accepted, 9 ms: map(2) {name=Ann, age=30} | error, 10 ms: IllegalStateException: com.fasterxml.jackson.dataformat.yaml.JacksonYAMLParseException: java.io.CharCon... |
| aliases_10000 | accepted, 63 ms: map(2) {defaults={timeout=30, retries=3, tags=[a, b, c]}, services=[{name=service0, set... | error, 14 ms: YAMLException: Number of aliases for non-scalar nodes exceeds the specified max=50 | accepted, 71 ms: map(2) {defaults={timeout=30, retries=3, tags=[a, b, c]}, services=[{name=service0, set... |
| invalid_tab_indent | error, 8 ms: YamlParseException: Tabs are not supported for indentation at line 2 | error, 8 ms: ScannerException: while scanning for the next token\nfound character '\t(TAB)' that cannot start a... | error, 22 ms: IllegalStateException: com.fasterxml.jackson.dataformat.yaml.snakeyaml.error.MarkedYAMLException: while... |
| empty_document | accepted, 1 ms: null | accepted, 4 ms: null | error, 10 ms: IllegalStateException: com.fasterxml.jackson.databind.exc.MismatchedInputException: No content to map d... |

## Cold start (JVM start to first parsed document, median of 7 runs)

| Library | ms |
|---|---|
| pure | 19 |
| snakeyaml | 32 |
| jackson | 84 |

Notes on the edge cases:

- `billion_laughs` and `aliases_10000`: Jackson "accepts" both because it does not resolve aliases;
  every alias is read as the anchor name (`"a0"`, `"defaults"`), so the result is silently wrong.
  io.pure resolves the 10 000 aliases of `aliases_10000` with its default limits and rejects the
  exponential expansion of `billion_laughs`.
- `huge_number_1e1000000000` and `duplicate_keys`: SnakeYAML and Jackson silently produce `Infinity`
  and keep the last duplicate; io.pure reports both.
- `unknown_tag_object`: no library constructs the object; SnakeYAML rejects global tags, io.pure and
  Jackson keep the data and ignore the tag.

## Footprint (runtime classpath)

| Library | Jars | Size | Classes |
|---|---|---|---|
| io.pure:yaml 1.3 | 1 | 117 KB | 57 |
| SnakeYAML 2.7 | 1 | 324 KB | 233 |
| jackson-dataformat-yaml 2.22.3 | 5 (dataformat-yaml, databind, core, annotations, snakeyaml) | 2 716 KB | 1 370 |
