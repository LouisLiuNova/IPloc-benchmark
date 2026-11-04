"""跨候选串行评估；保持旧 Java 九策略入口可复跑。"""

import hashlib
import json
import random
import struct
import subprocess
from pathlib import Path

COMMON = (
    "binary", "prefix16", "prefix20", "prefix16-24", "interleaved4", "interleaved8",
    "prefix16-interleaved4", "prefix16-interleaved8", "eytzinger",
)


def selection(candidate, algorithm, prefix=False):
    candidates = ["java", "rust-jni"] if candidate == "all" else [candidate]
    algorithms = list(COMMON) if algorithm == "legacy" else [algorithm]
    if prefix and algorithm == "legacy":
        algorithms = ["prefix16"]
    if any(a not in COMMON for a in algorithms):
        raise ValueError("不支持该策略")
    return candidates, algorithms


def order(candidates, algorithms):
    shuffled = list(algorithms)
    random.Random(20261104).shuffle(shuffled)
    return [
        (c, a)
        for i, a in enumerate(shuffled)
        for c in (candidates if i % 2 == 0 else list(reversed(candidates)))
    ]


def coverage(candidates, algorithms):
    return {
        (c, a, p, b, t, m, "real", 100)
        for c in candidates
        for a in algorithms
        for p in ("rows", "utf8", "strings")
        for b in (1024, 4096, 65536)
        for t in (1, 4, 8)
        for m in ("thrpt", "sample")
    }


def key(row):
    return (
        row["candidate"],
        row["algorithm"],
        row["path"],
        row["batch"],
        row["threads"],
        row["mode"],
        "synthetic" if row["dataset"].startswith("synthetic:") else "real",
        int(row["hitRate"]),
    )


def query_hash(count=None):
    """重现 java.util.Random，hash 为 Q=1M 的大端 IPv4 bit pattern。"""
    state = (20261104 ^ 0x5DEECE66D) & ((1 << 48) - 1)

    def bits(n):
        nonlocal state
        state = (state * 0x5DEECE66D + 11) & ((1 << 48) - 1)
        return state >> (48 - n)

    def bounded(bound):
        if bound & (bound - 1) == 0:
            return (bound * bits(31)) >> 31
        while True:
            b = bits(31)
            value = b % bound
            if b - value + bound - 1 < (1 << 31):
                return value

    digest = hashlib.sha256()
    for _ in range(1_000_000):
        ip = bits(32) if count is None else bounded(count) * 256 + bounded(128)
        digest.update(struct.pack(">I", ip))
    return digest.hexdigest()


def validate_resume(previous, current):
    for field, value in current.items():
        if field != "createdAt" and previous.get(field) != value:
            raise ValueError("resume 参数/源码/制品/数据不匹配: " + field)


def execute(args, parser, out, run):
    try:
        selected, algorithms = selection(args.candidate, args.algorithm, args.prefix)
    except ValueError as e:
        parser.error(str(e))
    if args.matrix_quick or args.only:
        parser.error("跨候选评估使用 --algorithms-quick、--quick 或 verify")
    if args.action == "benchmark" and not (args.quick or args.algorithms_quick):
        parser.error("本轮 Rust 仅开放快速评估；请指定 --quick 或 --algorithms-quick")
    if args.synthetic_verify and args.action != "verify":
        parser.error("--synthetic-verify 仅用于 verify")
    dataset = "synthetic:10000000" if args.synthetic_verify else args.dataset
    if args.action == "verify" and not args.synthetic_verify and not args.oracle:
        parser.error("verify 需要 --oracle oracle.tsv")
    native = Path(args.native).resolve()
    if "rust-jni" in selected and not native.is_file():
        parser.error(
            "缺少 Rust Native 库；请先运行 Maven -Prust-jni verify: " + str(native)
        )
    if not run.JAR.is_file():
        parser.error("缺少评估 JAR；请先构建项目")
    listing = subprocess.check_output(
        [str(Path(args.jdk) / "bin/jar"), "tf", str(run.JAR)], text=True
    )
    if (
        "rust-jni" in selected
        and "io/iploc/rust/RustIpRangeSnapshot.class" not in listing
    ):
        parser.error("评估 JAR 缺少 Rust 模块；请先运行 Maven -Prust-jni verify")
    if out.exists() and any(out.iterdir()) and not args.resume:
        parser.error("输出目录非空；使用新目录，或显式 --resume")
    java = str(Path(args.jdk) / "bin/java")
    version = subprocess.check_output(
        [java, "-version"], stderr=subprocess.STDOUT, text=True
    )
    if 'version "11.' not in version:
        parser.error("评估必须使用 JDK 11")

    def digest(path):
        with Path(path).open("rb") as f:
            return hashlib.file_digest(f, "sha256").hexdigest()

    pairs = order(selected, algorithms)
    names = [f"{c}-{a}" for c, a in pairs]
    action = (
        "synthetic"
        if args.synthetic_verify
        else (
            "verify"
            if args.action == "verify"
            else "preflight" if args.quick else "matrix"
        )
    )
    build = (
        json.loads(
            subprocess.check_output(
                [
                    java,
                    "-Diploc.native.path=" + str(native),
                    "-cp",
                    str(run.JAR),
                    "io.iploc.bench.CandidateInfoMain",
                ],
                text=True,
            )
        )
        if "rust-jni" in selected
        else None
    )
    rust = build["rustc"] if build else None
    manifest = {
        "crossCandidate": True,
        "action": action,
        "candidates": selected,
        "algorithms": algorithms,
        "plannedScenarios": names,
        "order": pairs,
        "algorithmsQuick": args.algorithms_quick,
        "quick": args.quick,
        "dataset": dataset,
        "sha256": (
            digest(dataset) if not args.synthetic_verify else "synthetic-v1-10000000"
        ),
        "oracleSha256": (
            digest(args.oracle) if args.oracle and not args.synthetic_verify else None
        ),
        "jarSha256": digest(run.JAR),
        "nativeSha256": digest(native) if "rust-jni" in selected else None,
        "rustToolchain": rust,
        "cargoLockSha256": digest(run.ROOT / "rust/Cargo.lock"),
        "build": build,
        "toolchainFileSha256": digest(run.ROOT / "rust/rust-toolchain.toml"),
        "querySha256": query_hash(10_000_000 if args.synthetic_verify else None),
        "git": subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=run.ROOT, text=True
        ).strip(),
        "jdk": version,
        "platform": run.platform.platform(),
        "cpuCount": run.os.cpu_count(),
        "seed": 20261104,
        "queryPool": 1_000_000,
        "createdAt": run.datetime.datetime.now(run.datetime.timezone.utc).isoformat(),
    }
    old = out / "manifest.json"
    if old.exists():
        try:
            validate_resume(json.loads(old.read_text()), manifest)
        except ValueError as e:
            parser.error(str(e))
    else:
        out.mkdir(parents=True, exist_ok=True)
        old.write_text(json.dumps(manifest, indent=2))
    for (candidate, algorithm), name in zip(pairs, names):
        directory = out / name
        status = directory / "status.json"
        if (
            args.resume
            and status.exists()
            and json.loads(status.read_text())["status"] == "passed"
        ):
            query_file = directory / "query-sha256.txt"
            if (
                not query_file.exists()
                or query_file.read_text().strip() != manifest["querySha256"]
            ):
                parser.error("resume 查询 hash 不匹配: " + name)
            continue
        command = [java, "-Xmx10g", "-Xms256m", f"-Xlog:gc:file={directory}/gc-%p.log"]
        if candidate == "rust-jni":
            command.append("-Diploc.native.path=" + str(native))
        command += [
            "-cp",
            str(run.JAR),
            "io.iploc.bench.CandidateRunMain",
            action,
            candidate,
            algorithm,
            dataset,
            str(directory),
        ]
        if action == "verify":
            command.append(str(Path(args.oracle).resolve()))
        print("START " + name, flush=True)
        result = run.guarded(command, directory)
        query_file = directory / "query-sha256.txt"
        if result["status"] == "passed" and (
            not query_file.exists()
            or query_file.read_text().strip() != manifest["querySha256"]
        ):
            result.update(status="failed", reason="Query input hash mismatch")
            status.write_text(json.dumps(result, indent=2))
        print(name + " " + result["status"], flush=True)
        run.report(out)
        if result["status"] != "passed":
            raise SystemExit(1)
    rows = run.report(out)
    if action == "matrix":
        summary = json.loads((out / "summary.json").read_text())
        c = summary["coverage"]
        if c["missing"] or c["unexpected"] or c["duplicates"]:
            raise SystemExit("矩阵覆盖不完整")
    print("DONE " + str(out) + " measurements=" + str(len(rows)), flush=True)
