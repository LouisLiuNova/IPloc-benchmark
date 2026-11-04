#!/usr/bin/env python3
"""生成小型跨候选 fixture；需要 PyArrow。"""

from pathlib import Path
import pyarrow as pa
import pyarrow.parquet as pq

ROOT = Path(__file__).resolve().parents[2]
FIELDS = [
    "country",
    "province",
    "scene",
    "isp",
    "owner",
    "areacode",
    "user",
    "asnumber",
]


def write(path, starts, ends, **overrides):
    n = len(starts)
    values = {
        "start_ip_int": pa.array(starts, pa.int64()),
        "end_ip_int": pa.array(ends, pa.int64()),
    }
    for f in FIELDS:
        values[f] = pa.array(
            [
                None if i % 3 == 0 else "" if i % 3 == 1 else "中国-" + f
                for i in range(n)
            ],
            pa.string(),
        )
    values.update(overrides)
    pq.write_table(pa.table(values), path, row_group_size=2, compression="snappy")


def main():
    out = ROOT / "tests/fixtures"
    out.mkdir(parents=True, exist_ok=True)
    starts = [0, 10, 2**31 - 1, 2**32 - 1]
    ends = [0, 20, 2**31 + 2, 2**32 - 1]
    write(out / "valid.parquet", starts, ends)
    write(out / "empty.parquet", [], [])
    write(out / "wide.parquet", [0, 0xFFFF0000], [0x80000000, 0xFFFFFFFF])
    for name, a, z in [
        ("overlap", [0, 0], [0, 2]),
        ("reversed", [2], [1]),
        ("negative", [-1], [1]),
        ("overflow", [0], [2**32]),
        ("null", [None], [1]),
        ("cross-group", [0, 10, 9], [0, 10, 12]),
    ]:
        write(out / (name + ".parquet"), a, z)
    write(
        out / "wrong-type.parquet",
        starts,
        ends,
        start_ip_int=pa.array(starts, pa.uint32()),
    )
    write(
        out / "wrong-payload.parquet",
        starts,
        ends,
        country=pa.array(starts, pa.int64()),
    )
    t = pq.read_table(out / "valid.parquet")
    pq.write_table(t.drop(["country"]), out / "missing.parquet")
    # 跨内部块并跨 Row Group；保持 fixture 较小。
    n = 65538
    values = {
        "start_ip_int": pa.array(range(n), pa.int64()),
        "end_ip_int": pa.array(range(n), pa.int64()),
    }
    values.update({f: pa.array(["x"] * n) for f in FIELDS})
    pq.write_table(
        pa.table(values),
        out / "chunks.parquet",
        row_group_size=40000,
        compression="snappy",
    )


if __name__ == "__main__":
    main()
