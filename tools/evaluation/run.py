#!/usr/bin/env python3
"""串行评估编排；只终止自行创建的进程组。所有运行结果写入独立目录。"""

import argparse, csv, datetime, hashlib, json, os, platform, signal, subprocess, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAR = ROOT / "java/iprange-bench/target/iprange-bench.jar"
GIB = 1024**3


def available():
    for line in Path("/proc/meminfo").read_text().splitlines():
        if line.startswith("MemAvailable:"):
            return int(line.split()[1]) * 1024
    raise RuntimeError("MemAvailable unavailable")


def group_usage(pgid):
    rss = cpu = 0
    for p in Path("/proc").iterdir():
        if not p.name.isdigit():
            continue
        try:
            fields = (p / "stat").read_text().rsplit(")", 1)[1].split()
            if int(fields[2]) != pgid:
                continue
            cpu += (int(fields[11]) + int(fields[12])) / os.sysconf("SC_CLK_TCK")
            rss += int(fields[21]) * os.sysconf("SC_PAGE_SIZE")
        except (OSError, ValueError, IndexError):
            pass
    return rss, cpu


def terminate_group(process):
    """父进程退出也要清理 fork；最多等待 5 秒后强制结束同组残留。"""
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        process.poll()
        try:
            os.killpg(process.pid, 0)
        except ProcessLookupError:
            return
        time.sleep(0.05)
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait()


def guarded(command, directory):
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "command.json").write_text(
        json.dumps(command, ensure_ascii=False, indent=2)
    )
    if available() < 2 * GIB:
        result = {
            "status": "resource_stopped",
            "reason": "MemAvailable below 2 GiB before launch",
            "command": command,
        }
        (directory / "status.json").write_text(json.dumps(result, indent=2))
        return result
    start = time.monotonic()
    bad = 0
    reason = None
    peak = 0
    with (
        (directory / "console.log").open("w") as log,
        (directory / "resources.csv").open("w") as samples,
    ):
        writer = csv.writer(samples)
        writer.writerow(
            ["elapsedSeconds", "groupRssBytes", "memAvailableBytes", "groupCpuSeconds"]
        )
        process = subprocess.Popen(
            command,
            cwd=ROOT,
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            while process.poll() is None:
                rss, cpu = group_usage(process.pid)
                mem = available()
                peak = max(peak, rss)
                writer.writerow(
                    [round(time.monotonic() - start, 3), rss, mem, round(cpu, 3)]
                )
                samples.flush()
                bad = bad + 1 if mem < 2 * GIB or rss > 12 * GIB else 0
                if bad >= 2:
                    reason = "MemAvailable < 2 GiB or process-group RSS > 12 GiB for two samples"
                    terminate_group(process)
                    break
                time.sleep(1)
        finally:
            # JMH fork 也在本进程组；父进程异常退出时不能遗留 fork。
            terminate_group(process)
        code = process.wait()
    result = {
        "status": "resource_stopped" if reason else "passed" if code == 0 else "failed",
        "exitCode": code,
        "reason": reason,
        "seconds": round(time.monotonic() - start, 3),
        "peakGroupRssBytes": peak,
        "command": command,
    }
    (directory / "status.json").write_text(json.dumps(result, indent=2))
    return result


def scenarios(dataset, quick):
    if quick:
        return [("preflight", dataset, 4096, 1, "rows|utf8|strings|parsed", False, 100)]
    cases = []
    for b in [1024, 4096, 65536]:
        for c in [1, 4, 8]:
            cases.append(
                (f"real-b{b}-c{c}", dataset, b, c, "rows|utf8|strings", False, 100)
            )
    for b in [1, 256, 1000000]:
        cases.append((f"extra-b{b}", dataset, b, 1, "rows|utf8|strings", False, 100))
    for hit in [0, 50, 90]:
        for c in [1, 4]:
            cases.append(
                (
                    f"synthetic-h{hit}-c{c}",
                    "synthetic:10000000",
                    4096,
                    c,
                    "rows|utf8|strings",
                    False,
                    hit,
                )
            )
    for b in [1024, 4096, 65536]:
        for c in [1, 4, 8]:
            cases.append((f"prefix-b{b}-c{c}", dataset, b, c, "rows", True, 100))
    cases.append(("parsed", dataset, 4096, 1, "parsed", False, 100))
    return cases


def sample_count(metric):
    histogram = metric.get("rawDataHistogram")
    if histogram is None:
        return None
    return sum(
        int(count) for fork in histogram for iteration in fork for _, count in iteration
    )


def coverage_key(path, batch, threads, prefix, dataset, hit, mode):
    return (
        path,
        int(batch),
        int(threads),
        str(prefix).lower(),
        "synthetic" if dataset.startswith("synthetic:") else "real",
        int(hit),
        mode,
    )


def expected_coverage(dataset):
    return {
        coverage_key(path, b, c, prefix, data, hit, mode)
        for _, data, b, c, paths, prefix, hit in scenarios(dataset, False)
        for path in paths.split("|")
        for mode in ["thrpt", "sample"]
    }


def report(directory):
    rows = []
    statuses = []
    for path in sorted(directory.glob("*/status.json")):
        s = json.loads(path.read_text())
        statuses.append({"scenario": path.parent.name, **s})
    for path in sorted(directory.glob("*/jmh*.json")):
        try:
            results = json.loads(path.read_text())
        except (ValueError, OSError):
            continue
        status_path = path.parent / "status.json"
        if (
            not status_path.exists()
            or json.loads(status_path.read_text())["status"] != "passed"
        ):
            continue
        for r in results:
            m = r["primaryMetric"]
            batch = int(r["params"]["batch"])
            percentiles = m.get("scorePercentiles", {}) if r["mode"] == "sample" else {}
            rows.append(
                {
                    "scenario": path.parent.name,
                    "path": r["benchmark"].rsplit(".", 1)[-1],
                    "mode": r["mode"],
                    "batch": batch,
                    "threads": r["threads"],
                    "prefix": r["params"]["prefix"],
                    "dataset": r["params"]["dataset"],
                    "hitRate": r["params"]["hitRate"],
                    "score": m["score"],
                    "scoreError": m["scoreError"],
                    "scoreUnit": m["scoreUnit"],
                    "ipPerSecond": m["score"] * batch if r["mode"] == "thrpt" else None,
                    "p50": percentiles.get("50.0"),
                    "p95": percentiles.get("95.0"),
                    "p99": percentiles.get("99.0"),
                    "samples": sample_count(m),
                    "tailSufficient": sample_count(m) is not None
                    and sample_count(m) >= 10000,
                }
            )
    manifest_path = directory / "manifest.json"
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text())
        present = {s["scenario"] for s in statuses}
        for name in manifest.get("plannedScenarios", []):
            if name not in present:
                statuses.append({"scenario": name, "status": "not_run"})
    coverage = None
    if manifest_path.exists() and manifest.get("matrixQuick"):
        expected = expected_coverage(manifest["dataset"])
        actual = {
            coverage_key(
                r["path"],
                r["batch"],
                r["threads"],
                r["prefix"],
                r["dataset"],
                r["hitRate"],
                r["mode"],
            )
            for r in rows
        }
        coverage = {
            "expected": len(expected),
            "actual": len(actual),
            "missing": sorted(expected - actual),
            "unexpected": sorted(actual - expected),
        }
    (directory / "summary.json").write_text(
        json.dumps(
            {
                "rust-jni": "not_implemented",
                "coverage": coverage,
                "runs": statuses,
                "measurements": rows,
            },
            indent=2,
        )
    )
    lines = [
        "# Java 性能评估结果",
        "\n全矩阵快速模式：1 次预热 + 1 次测量，各 1 秒；JMH forks=0，每进程复用快照，共享 JIT/GC 历史。仅作探索性比较，正式长测待运行。"
        if manifest_path.exists()
        and json.loads(manifest_path.read_text()).get("matrixQuick")
        else "",
        "",
        "Rust/JNI：not_implemented，尚无跨候选对比。",
        "",
        "吞吐包含结果消费；sample 延迟单位为秒/批次。尾延迟样本少于 10,000 时标为不足（P99 尾部不足约 100 个样本）；不宣称稳定。",
        "",
        "| 场景 | 路径 | 模式 | B | C | IP/s 或秒/批次 | P50 | P95 | P99 | 样本数 / 尾延迟证据 |",
        "|---|---|---|---:|---:|---:|---:|---:|---:|---|",
    ]
    for r in rows:
        val = r["ipPerSecond"] if r["ipPerSecond"] is not None else r["score"]
        lines.append(
            f"| {r['scenario']} | {r['path']} | {r['mode']} | {r['batch']} | {r['threads']} | {val:.6g} | {r['p50']} | {r['p95']} | {r['p99']} | {r['samples']} / {'足够' if r['tailSufficient'] else '不足' if r['mode'] == 'sample' else '不适用'} |"
        )
    lines += ["", "## 运行状态", ""] + [
        f"- {s['scenario']}: {s['status']}，{s.get('seconds', '-')} 秒，峰值进程组 RSS {s.get('peakGroupRssBytes', 0) / GIB:.2f} GiB。"
        for s in statuses
    ]
    (directory / "report.md").write_text("\n".join(lines) + "\n")
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["verify", "benchmark", "report"])
    parser.add_argument(
        "--candidate", choices=["java", "rust-jni", "all"], default="java"
    )
    parser.add_argument("--dataset", default=str(ROOT / "iploc.parquet"))
    parser.add_argument("--output")
    parser.add_argument("--jdk", default="/usr/lib/jvm/java-11-openjdk-amd64")
    parser.add_argument("--quick", action="store_true")
    parser.add_argument(
        "--matrix-quick",
        action="store_true",
        help="全矩阵探索性测量：每进程共用快照，JMH forks=0",
    )
    parser.add_argument("--oracle")
    parser.add_argument("--prefix", action="store_true", help="verify 时启用前缀目录")
    parser.add_argument("--only", help="只运行名称包含该文本的场景")
    parser.add_argument(
        "--resume", action="store_true", help="跳过同一目录中已通过的场景"
    )
    args = parser.parse_args()
    if args.quick and args.matrix_quick:
        parser.error("--quick 与 --matrix-quick 互斥")
    if args.matrix_quick and args.only:
        parser.error("全矩阵快速模式不接受 --only")
    if args.candidate == "rust-jni":
        parser.error("rust-jni: not_implemented")
    out = Path(
        args.output
        or ROOT
        / "benchmark-results"
        / datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    ).resolve()
    if args.action == "report":
        report(out)
        print(out / "report.md")
        return
    if out.exists() and any(out.iterdir()) and not args.resume:
        parser.error("输出目录非空；使用新目录，或显式 --resume")
    out.mkdir(parents=True, exist_ok=True)
    java = str(Path(args.jdk) / "bin/java")
    version = subprocess.check_output(
        [java, "-version"], stderr=subprocess.STDOUT, text=True
    )
    if 'version "11.' not in version:
        parser.error("评估必须使用 JDK 11")
    digest = hashlib.file_digest(open(args.dataset, "rb"), "sha256").hexdigest()
    manifest = {
        "jarSha256": hashlib.file_digest(JAR.open("rb"), "sha256").hexdigest(),
        "plannedScenarios": ["verify"]
        if args.action == "verify"
        else ["quick-real", "quick-synthetic", "quick-prefix"]
        if args.matrix_quick
        else [
            c[0]
            for c in scenarios(args.dataset, args.quick)
            if not args.only or args.only in c[0]
        ],
        "candidate": "java",
        "rust-jni": "not_implemented",
        "dataset": args.dataset,
        "sha256": digest,
        "jdk": version,
        "platform": platform.platform(),
        "cpuCount": os.cpu_count(),
        "seed": 20261104,
        "queryPool": 1000000,
        "quick": args.quick,
        "matrixQuick": args.matrix_quick,
        "createdAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "git": subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
        ).strip(),
    }
    if not manifest["plannedScenarios"]:
        parser.error("没有匹配的评估场景")
    old = out / "manifest.json"
    if old.exists():
        previous = json.loads(old.read_text())
        for key in [
            "sha256",
            "quick",
            "git",
            "jdk",
            "candidate",
            "jarSha256",
            "plannedScenarios",
            "matrixQuick",
        ]:
            if previous.get(key) != manifest.get(key):
                parser.error("resume 参数/源码/数据不匹配: " + key)
    else:
        old.write_text(json.dumps(manifest, indent=2))
    if args.candidate == "all":
        print("rust-jni: skipped (not_implemented)", flush=True)
    if args.action == "verify":
        if not args.oracle:
            parser.error("verify 需要 --oracle oracle.tsv")
        command = [
            java,
            "-Xmx10g",
            "-cp",
            str(JAR),
            "io.iploc.bench.VerifyMain",
            args.dataset,
            str(Path(args.oracle).resolve()),
            str(args.prefix).lower(),
        ]
        result = guarded(command, out / "verify")
        print(json.dumps(result), flush=True)
        report(out)
        sys.exit(0 if result["status"] == "passed" else 1)
    if args.matrix_quick:
        for group, dataset in [
            ("real", args.dataset),
            ("synthetic", "synthetic:10000000"),
            ("prefix", args.dataset),
        ]:
            name = "quick-" + group
            directory = out / name
            if (
                args.resume
                and (directory / "status.json").exists()
                and json.loads((directory / "status.json").read_text())["status"]
                == "passed"
            ):
                continue
            command = [
                java,
                "-Xmx10g",
                "-Xms256m",
                f"-Xlog:gc:file={directory}/gc-%p.log",
                "-cp",
                str(JAR),
                "io.iploc.bench.QuickMatrixMain",
                group,
                dataset,
                str(directory),
            ]
            print("START " + name, flush=True)
            result = guarded(command, directory)
            print(name + " " + json.dumps(result), flush=True)
            report(out)
            if result["status"] != "passed":
                sys.exit(1)
        report(out)
        coverage = json.loads((out / "summary.json").read_text())["coverage"]
        if coverage["missing"] or coverage["unexpected"]:
            raise RuntimeError("快速矩阵覆盖不完整；详见 summary.json")
        print("DONE " + str(out), flush=True)
        return
    for name, dataset, b, c, paths, prefix, hit in scenarios(args.dataset, args.quick):
        if args.only and args.only not in name:
            continue
        directory = out / name
        if (
            args.resume
            and (directory / "status.json").exists()
            and json.loads((directory / "status.json").read_text())["status"]
            == "passed"
        ):
            continue
        directory.mkdir(parents=True, exist_ok=True)
        command = [
            java,
            "-Xmx256m",
            "-jar",
            str(JAR),
            f".*LookupBenchmark.({paths})$",
            "-p",
            f"dataset={dataset}",
            "-p",
            f"batch={b}",
            "-p",
            f"prefix={str(prefix).lower()}",
            "-p",
            f"hitRate={hit}",
            "-t",
            str(c),
            "-f",
            "1" if args.quick else "3",
            "-wi",
            "1" if args.quick else "5",
            "-i",
            "1" if args.quick else "10",
            "-w",
            "1s",
            "-r",
            "1s",
            "-bm",
            "thrpt,sample",
            "-foe",
            "true",
            "-prof",
            "gc",
            "-jvm",
            java,
            "-jvmArgs",
            f"-Xmx10g -Xms256m -Xlog:gc:file={directory}/gc-%p.log",
            "-rf",
            "json",
            "-rff",
            str(directory / "jmh.json"),
        ]
        print("START " + name, flush=True)
        result = guarded(command, directory)
        print(name + " " + json.dumps(result), flush=True)
        report(out)
        if result["status"] != "passed":
            sys.exit(1)
    report(out)
    print("DONE " + str(out), flush=True)


if __name__ == "__main__":
    main()
