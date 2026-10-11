# Benchmarks

Performance comparison of `io.pure:yaml` with SnakeYAML, snakeyaml-engine and Jackson
(`jackson-dataformat-yaml`). This module is not part of the library build. Results of the last run
are in [RESULTS.md](RESULTS.md).

```bash
# from the repository root: install the library, then build the benchmarks
mvn -q install -DskipTests -Djacoco.skip=true -Dspotbugs.skip=true
cd benchmarks && mvn -q package

# download the real-world documents (pinned versions, checked by SHA-256) into data/
./fetch-data.sh

# everything: validation, JMH with allocation profiling (about 2 h 15 min), sizes, edge cases, cold start
./run-all.sh
python3 report.py target

# or a single JMH class, for example
java -jar target/benchmarks.jar RealDocumentBenchmark -prof gc -rf json -rff target/RealDocumentBenchmark.json
```

JMH settings: 3 forks with `-Xms4g -Xmx4g -XX:+AlwaysPreTouch`, average time per operation, single
thread. Warmup and measurement are 5 × 2 s, except the 10 MB inputs (5 × 3 s warmup, 5 × 5 s
measurement) and the real documents (5 × 3 s measurement).

## Classes

- `bench.YamlLib`: the same operations on each library, in three modes:
  - `DEFAULTS`: each library exactly as shipped (edge cases, cold start, written output);
  - `ALIASES_ENABLED`: defaults, except that alias resolution is enabled or its limit raised to
    resolve 10 000 aliases (the `aliases_10000_enabled` edge case);
  - `BENCHMARK`: raised size, depth and alias limits so that every input is accepted, and the YAML 1.2
    core schema for snakeyaml-engine (its default is the JSON schema).
- `bench.ParseBenchmark`: synthetic inputs parsed to the library's tree and to maps and lists, with
  all four libraries.
- `bench.BindWriteBenchmark`: binding to Java objects and writing them; snakeyaml-engine has no
  object binding and is not included.
- `bench.RealDocumentBenchmark`: every document of each real-world file, to the tree and to maps and
  lists.
- `bench.Data`: deterministic synthetic inputs (configuration, records in block and JSON style,
  aliases, block scalars, document streams, pathological inputs) and the loader of `data/`
  (system property `bench.data` to read it from another directory).
- `bench.Validate`: checks that every library reads every real-world document, and that io.pure and
  snakeyaml-engine produce equal maps and lists.
- `bench.Sizes`: sizes of all inputs.
- `bench.EdgeCases`: one edge case per JVM; prints `RESULT|outcome|ms|detail`, where the outcome is
  `accepted`, `rejected` (an exception) or `crashed` (an `Error` such as `StackOverflowError`).
- `bench.EdgeRunner`: runs all edge cases with all libraries (`-Xmx512m -Xss2m`, 30 s timeout), cold
  start with the default JIT and with C1 only (`bench.ColdStart`, median of 7 runs), and the size of
  the written output.

## Real-world documents

Downloaded by `fetch-data.sh` and not committed. None of them uses merge keys (`<<`), which io.pure
does not support.

| File | Source | License | Size | Documents |
| --- | --- | --- | --- | --- |
| `openapi-github.yaml` | GitHub REST API description, [github/rest-api-description](https://github.com/github/rest-api-description) | MIT | 9.4 MB | 1 |
| `cert-manager.yaml` | cert-manager v1.21.2 release manifest (CRDs), [cert-manager/cert-manager](https://github.com/cert-manager/cert-manager) | Apache-2.0 | 1010 KB | 47 |
| `helm-values.yaml` | kube-prometheus-stack chart values, mostly comments, [prometheus-community/helm-charts](https://github.com/prometheus-community/helm-charts) | Apache-2.0 | 214 KB | 1 |
| `faker-ru.yml` | Russian locale of Faker, Cyrillic text, [faker-ruby/faker](https://github.com/faker-ruby/faker) | MIT | 44 KB | 1 |
| `ingress-nginx.yaml` | ingress-nginx controller v1.15.1 deployment manifest, [kubernetes/ingress-nginx](https://github.com/kubernetes/ingress-nginx) | Apache-2.0 | 16 KB | 19 |
| `compose.yaml` | React, Express and MySQL sample, [docker/awesome-compose](https://github.com/docker/awesome-compose) | CC0-1.0 | 1.5 KB | 1 |
