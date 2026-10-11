#!/usr/bin/env python3
"""Turns the JMH JSON results in target/ into Markdown tables: time per operation, speed-up ratios and allocation per operation."""
import json
import os
import sys
from collections import defaultdict

LIBS = ["pure", "snakeyaml", "engine", "jackson"]
OTHERS = ["snakeyaml", "engine", "jackson"]
RESULTS = ["ParseBenchmark.json", "BindWriteBenchmark.json", "RealDocumentBenchmark.json"]
# Input sizes in bytes of the synthetic inputs (see bench.Sizes).
SIZES = {
    "tree_small": 1051, "untyped_small": 1051,
    "tree_medium": 104084, "untyped_medium": 104084, "bind_medium": 104084,
    "tree_large": 10807234, "untyped_large": 10807234, "bind_large": 10807234,
    "json_untyped": 1905905, "anchors_untyped": 458965, "blockScalars_tree": 1565800, "stream_1000": 44780,
}
SYNTHETIC = [
    "tree_small", "tree_medium", "tree_large", "untyped_small", "untyped_medium", "untyped_large",
    "json_untyped", "anchors_untyped", "blockScalars_tree", "stream_1000",
    "bind_medium", "bind_large", "write_medium", "write_large",
]
DOCUMENTS = ["openapi-github.yaml", "cert-manager.yaml", "helm-values.yaml", "faker-ru.yml", "ingress-nginx.yaml", "compose.yaml"]


def load(directory):
    rows = defaultdict(dict)
    for file in RESULTS:
        path = os.path.join(directory, file)
        if not os.path.exists(path):
            continue
        for entry in json.load(open(path)):
            name = entry["benchmark"].split(".")[-1]
            if "document" in entry["params"]:
                name = entry["params"]["document"] + " " + name
            metric = entry["primaryMetric"]
            rows[name][entry["params"]["lib"]] = {
                "score": metric["score"],
                "error": metric["scoreError"],
                "alloc": entry["secondaryMetrics"].get("gc.alloc.rate.norm", {}).get("score"),
            }
    return rows


def fmt_time(value):
    if value >= 1_000_000:
        return f"{value / 1_000_000:.2f} s"
    if value >= 1_000:
        return f"{value / 1_000:.2f} ms"
    return f"{value:.1f} µs"


def fmt_bytes(value):
    if value is None:
        return "n/a"
    if value >= 1 << 20:
        return f"{value / (1 << 20):.1f} MB"
    if value >= 1 << 10:
        return f"{value / (1 << 10):.0f} KB"
    return f"{value:.0f} B"


def ratios(row, key):
    pure = row.get("pure", {}).get(key)
    result = []
    for lib in OTHERS:
        other = row.get(lib, {}).get(key)
        result.append("n/a" if not pure or not other else f"{other / pure:.1f}×")
    return result


def time_cell(row, lib):
    r = row.get(lib)
    if r is None:
        return "n/a"
    return f"{fmt_time(r['score'])} ± {r['error'] / r['score'] * 100:.0f}%"


def throughput(size, row):
    pure = row.get("pure", {}).get("score")
    return "n/a" if not size or not pure else f"{size / pure:.0f}"


def header(first, second):
    print(f"| {first} | {second} | " + " | ".join(LIBS) + " | pure MB/s | vs SnakeYAML | vs engine | vs Jackson |")
    print("|---|---|" + "---|" * (len(LIBS) + 4))


def main(directory):
    rows = load(directory)
    names = [name for name in SYNTHETIC if name in rows]
    print("## Synthetic inputs: time per operation (lower is better; ± is the 99.9% confidence interval)\n")
    header("Benchmark", "Input")
    for name in names:
        size = SIZES.get(name)
        cells = [fmt_bytes(size) if size else "–"] + [time_cell(rows[name], lib) for lib in LIBS]
        cells.append(throughput(size, rows[name]))
        print(f"| {name} | " + " | ".join(cells + ratios(rows[name], "score")) + " |")

    documents = [(document, op) for document in DOCUMENTS for op in ["tree", "untyped"] if f"{document} {op}" in rows]
    if documents:
        print("\n## Real documents: time per operation (lower is better)\n")
        header("Document", "Operation")
        for document, op in documents:
            row = rows[f"{document} {op}"]
            path = os.path.join(directory, "..", "data", document)
            size = os.path.getsize(path) if os.path.exists(path) else None
            cells = [op] + [time_cell(row, lib) for lib in LIBS] + [throughput(size, row)]
            print(f"| {document} ({fmt_bytes(size)}) | " + " | ".join(cells + ratios(row, "score")) + " |")

    print("\n## Allocation per operation (lower is better)\n")
    print("| Benchmark | " + " | ".join(LIBS) + " | vs SnakeYAML | vs engine | vs Jackson |")
    print("|---|" + "---|" * (len(LIBS) + 3))
    for name in names + [f"{document} {op}" for document, op in documents]:
        cells = [fmt_bytes(rows[name].get(lib, {}).get("alloc")) for lib in LIBS]
        print(f"| {name} | " + " | ".join(cells + ratios(rows[name], "alloc")) + " |")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "target")
