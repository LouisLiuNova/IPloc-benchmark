#!/usr/bin/env python3
"""导出可提交的小型指标证据；保留原始指标，只归一化机器路径。"""

import argparse
import csv
import gzip
import hashlib
import json
import re
from pathlib import Path
import run


def export(source, prefix):
    run.report(source)
    summary = json.loads((source / "summary.json").read_text())
    manifest = json.loads((source / "manifest.json").read_text())
    coverage = summary.get("coverage")
    if (
        not coverage
        or coverage["actual"] != coverage["expected"]
        or coverage["missing"]
        or coverage["unexpected"]
        or coverage.get("duplicates", 0)
    ):
        raise ValueError("只导出覆盖完整的快速矩阵")
    if not summary["runs"] or any(r["status"] != "passed" for r in summary["runs"]):
        raise ValueError("存在未通过或未完成的运行")
    measurements = summary["measurements"]
    for item in measurements:
        if not item["dataset"].startswith("synthetic:"):
            item["dataset"] = Path(item["dataset"]).name
    runs = []
    resources = {}
    index_stats = []
    order = None
    for entry in summary["runs"]:
        directory = source / entry["scenario"]
        samples = list(csv.DictReader((directory / "resources.csv").open()))
        console = (directory / "console.log").read_text()
        loads = re.findall(r"LOAD rows=(\d+) version=(\S+) seconds=([\d.]+)", console)
        runs.append(
            {
                "scenario": entry["scenario"],
                "status": entry["status"],
                "seconds": entry["seconds"],
                "peakGroupRssBytes": entry["peakGroupRssBytes"],
                "minimumMemAvailableBytes": min(
                    int(s["memAvailableBytes"]) for s in samples
                ),
                "loadSeconds": [float(s[2]) for s in loads],
                "loadedRows": [int(s[0]) for s in loads],
            }
        )
        resources[entry["scenario"]] = (directory / "resources.csv").read_text()
        index_stats.extend(
            {"scenario": entry["scenario"], **json.loads(p.read_text())}
            for p in sorted(directory.glob("index-*.json"))
        )
        if (directory / "order.txt").exists():
            order = (directory / "order.txt").read_text()
    results = []
    original_files = []
    for path in sorted(source.glob("*/jmh*.json")):
        original_files.append(
            {
                "file": str(path.relative_to(source)),
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            }
        )
        for result in json.loads(path.read_text()):
            result["jvm"] = "<JDK11>/bin/java"
            result["jvmArgs"] = [
                (
                    "-Xlog:gc:file=<run-dir>/gc-%p.log"
                    if a.startswith("-Xlog:gc:file=")
                    else (
                        "-Diploc.native.path=<native-library>"
                        if a.startswith("-Diploc.native.path=")
                        else a
                    )
                )
                for a in result.get("jvmArgs", [])
            ]
            if not result["params"]["dataset"].startswith("synthetic:"):
                result["params"]["dataset"] = Path(result["params"]["dataset"]).name
            results.append(result)
    cpu = next(
        (
            line.split(":", 1)[1].strip()
            for line in Path("/proc/cpuinfo").read_text().splitlines()
            if line.startswith("model name")
        ),
        "unknown",
    )
    artifact = {
        "sourceCommit": manifest["git"],
        "jarSha256": manifest["jarSha256"],
        "datasetSha256": manifest["sha256"],
        "seed": manifest["seed"],
        "queryPool": manifest["queryPool"],
        "mode": (
            "exploratory-non-forked-per-candidate-algorithm-process"
            if manifest.get("crossCandidate")
            else "exploratory-non-forked-shared-snapshot"
        ),
        "candidates": manifest.get("candidates", ["java"]),
        "candidateAlgorithmOrder": manifest.get("order"),
        "nativeSha256": manifest.get("nativeSha256"),
        "rustToolchain": manifest.get("rustToolchain"),
        "cargoLockSha256": manifest.get("cargoLockSha256"),
        "build": manifest.get("build"),
        "querySha256": manifest.get("querySha256"),
        "warmup": "1 x 1 second",
        "measurement": "1 x 1 second",
        "jdk": manifest["jdk"],
        "platform": manifest["platform"],
        "logicalCpuCount": manifest["cpuCount"],
        "cpuModel": cpu,
        "coverage": coverage,
        "runs": runs,
        "algorithmOrder": order,
        "indexStats": index_stats,
        "originalJmhFiles": original_files,
        "measurements": measurements,
    }
    prefix.parent.mkdir(parents=True, exist_ok=True)
    Path(str(prefix) + ".json").write_text(
        json.dumps(artifact, ensure_ascii=False, indent=2) + "\n"
    )
    raw = {
        "normalization": "仅归一化 JDK、数据文件和 GC 输出机器路径；JMH 指标、原始测量及直方图未改写。原文件 hash 见指标摘要。",
        "results": results,
        "resources": resources,
    }
    Path(str(prefix) + "-raw.json.gz").write_bytes(
        gzip.compress(
            json.dumps(raw, ensure_ascii=False, separators=(",", ":")).encode(), mtime=0
        )
    )
    print(f"Exported {len(results)} measurements to {prefix}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("prefix", type=Path)
    args = parser.parse_args()
    export(args.source.resolve(), args.prefix)
