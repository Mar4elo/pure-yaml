# Benchmarks

Performance comparison of `io.pure:yaml` with SnakeYAML and Jackson (`jackson-dataformat-yaml`).
This module is not part of the library build. Results of the last run are in [RESULTS.md](RESULTS.md).

```bash
# from the repository root: install the library, then build the benchmarks
mvn -q install -DskipTests -Djacoco.skip=true -Dspotbugs.skip=true
cd benchmarks && mvn -q package

# throughput and allocation (JMH, about 20 minutes)
java -jar target/benchmarks.jar YamlBenchmark -f 1 -wi 3 -w 1s -i 5 -r 1s -prof gc -rf json -rff target/jmh.json
python3 report.py target/jmh.json

# edge cases with default settings, each in its own JVM, and cold start
java -cp target/benchmarks.jar bench.EdgeRunner
```

- `bench.YamlLib`: the same operations on each library; the JMH benchmarks use raised limits so that
  every input is accepted, the edge cases use each library as shipped.
- `bench.Data`: deterministic inputs (configuration, records in block and JSON style, aliases,
  block scalars, document streams, pathological inputs).
- `bench.EdgeCases`: one edge case per JVM; prints `RESULT|outcome|ms|detail`.
