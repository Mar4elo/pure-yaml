#!/usr/bin/env python3
"""Turns target/jmh.json into Markdown tables: time per operation, speed-up ratios and allocation per operation."""
import json
import sys
from collections import defaultdict

LIBS = ["pure", "snakeyaml", "jackson"]
# Input sizes in characters (ASCII, so bytes), used to derive MB/s for the parse benchmarks.
SIZES = {
    "tree_small": 1051, "untyped_small": 1051,
    "tree_medium": 104084, "untyped_medium": 104084, "bind_medium": 104084,
    "tree_large": 10807234, "untyped_large": 10807234, "bind_large": 10807234,
    "json_untyped": 1905905, "anchors_untyped": 458965, "blockScalars_tree": 1565800, "stream_1000": 44780,
}


def load(path):
    rows = defaultdict(dict)
    for entry in json.load(open(path)):
        name = entry["benchmark"].split(".")[-1]
        lib = entry["params"]["lib"]
        unit = entry["primaryMetric"]["scoreUnit"]
        score = entry["primaryMetric"]["score"]
        error = entry["primaryMetric"]["scoreError"]
        alloc = entry["secondaryMetrics"].get("gc.alloc.rate.norm", {}).get("score")
        rows[name][lib] = {"score": score, "error": error, "unit": unit, "alloc": alloc}
    return rows


def fmt_time(value, unit):
    if unit == "us/op":
        if value >= 1_000_000:
            return f"{value / 1_000_000:.2f} s"
        if value >= 1_000:
            return f"{value / 1_000:.2f} ms"
        return f"{value:.1f} µs"
    return f"{value:.1f} {unit}"


def fmt_bytes(value):
    if value is None:
        return "–"
    if value >= 1 << 20:
        return f"{value / (1 << 20):.1f} MB"
    if value >= 1 << 10:
        return f"{value / (1 << 10):.0f} KB"
    return f"{value:.0f} B"


def main(path):
    rows = load(path)
    order = [name for name in [
        "tree_small", "tree_medium", "tree_large", "untyped_small", "untyped_medium", "untyped_large",
        "bind_medium", "bind_large", "write_medium", "write_large", "json_untyped", "anchors_untyped",
        "blockScalars_tree", "stream_1000",
    ] if name in rows]
    print("## Time per operation (lower is better; ± is the 99.9% confidence interval)\n")
    print("| Benchmark | Input | pure | snakeyaml | jackson | vs SnakeYAML | vs Jackson |")
    print("|---|---|---|---|---|---|---|")
    for name in order:
        cells = [fmt_bytes(SIZES[name]) if name in SIZES else "–"]
        for lib in LIBS:
            r = rows[name].get(lib)
            if r is None:
                cells.append("–")
                continue
            cell = f"{fmt_time(r['score'], r['unit'])} ± {r['error'] / r['score'] * 100:.0f}%"
            if name in SIZES and r["unit"] == "us/op":
                cell += f" ({SIZES[name] / r['score']:.0f} MB/s)"
            cells.append(cell)
        pure = rows[name].get("pure", {}).get("score")
        ratios = []
        for lib in ["snakeyaml", "jackson"]:
            other = rows[name].get(lib, {}).get("score")
            ratios.append("–" if not pure or not other else f"{other / pure:.1f}×")
        print(f"| {name} | " + " | ".join(cells + ratios) + " |")

    print("\n## Allocation per operation (lower is better)\n")
    print("| Benchmark | pure | snakeyaml | jackson | vs SnakeYAML | vs Jackson |")
    print("|---|---|---|---|---|---|")
    for name in order:
        cells = [fmt_bytes(rows[name].get(lib, {}).get("alloc")) for lib in LIBS]
        pure = rows[name].get("pure", {}).get("alloc")
        ratios = []
        for lib in ["snakeyaml", "jackson"]:
            other = rows[name].get(lib, {}).get("alloc")
            ratios.append("–" if not pure or not other else f"{other / pure:.1f}×")
        print(f"| {name} | " + " | ".join(cells + ratios) + " |")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "target/jmh.json")
