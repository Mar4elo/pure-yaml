#!/bin/sh
# Full run: JMH with allocation profiling (about 2 h 15 min), then edge cases, cold start and sizes.
# Results go to target/; report.py turns the JSON files into Markdown tables.
set -eu
cd "$(dirname "$0")"
java -cp target/benchmarks.jar bench.Validate
for benchmark in ParseBenchmark BindWriteBenchmark RealDocumentBenchmark; do
    java -jar target/benchmarks.jar "$benchmark" -prof gc -rf json -rff "target/$benchmark.json" \
        | tee "target/$benchmark.log"
done
java -cp target/benchmarks.jar bench.Sizes | tee target/sizes.md
java -cp target/benchmarks.jar bench.EdgeRunner | tee target/edge.md
