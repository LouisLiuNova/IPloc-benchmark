# Rust 搜索策略与验证入口

Rust/JNI 与 Java 现均支持九种策略，默认 `binary`。本次在 `244786c` 主分支基础上补齐六种 Rust 策略，查询、载荷、JNI 和生命周期契约沿用现有实现，不新增依赖。算法语义以项目已有 [Java 策略实现](../java/iprange-java/src/main/java/io/iploc/java/SearchIndexes.java)为对照，Rust 实现位于 [core](../rust/core/src/lib.rs)。

## 策略和额外索引

| ID | 查询方式 | 额外基本类型数组 |
|---|---|---|
| `binary` | 全局有序起点前驱二分，再检查闭区间终点 | 0 |
| `prefix16` | /16 目录限制二分范围 | 262,148 字节 |
| `prefix20` | /20 目录限制二分范围 | 4,194,308 字节 |
| `prefix16-24` | /16 桶内起点数达到 4,096 时，建立 257 项 /24 二级目录 | 262,148 + 1,028 × 密集桶数，字节 |
| `interleaved4` | 交替推进四个独立 IP 的二分步骤 | 0 |
| `interleaved8` | 交替推进八个独立 IP 的二分步骤 | 0 |
| `prefix16-interleaved4` | /16 目录与四路交错二分 | 262,148 字节 |
| `prefix16-interleaved8` | /16 目录与八路交错二分 | 262,148 字节 |
| `eytzinger` | 一基树式布局起点与原始行号映射，遍历后检查终点 | 8 × (N + 1)，字节 |

前缀目录记录每个桶内第一个起点的行偏移，查找保留全局前驱候选，因此跨 /16、/20、/24 的长区间不会丢失。空桶、空数据和 IPv4 无符号上界同样适用。两级目录按起点密度细分，与区间覆盖长度无关。

交错实现使用 Rust const generics 固定为四路或八路，工作数组位于调用栈；不足一路组的尾部回退到同策略的标量二分。交错表示一个调用内交替推进独立查找，不创建线程。外部 Java 线程仍可只读共享快照。

Eytzinger 按中序遍历将有序起点填入完全二叉树的数组位置，查询记录最后一个不大于 IP 的节点，再通过映射恢复 Parquet 原始行号。载荷和终点不重排，返回的行号契约与其他策略一致。

`indexBytes()` 与 Java 相同，只统计额外 `u32` 数组的逻辑字节数，不含基础边界、载荷、容器、二级目录指针、对象头、分配器开销或临时内存，不能作为 RSS。视图共享不可变数据，各自持有索引及生命周期；不会复制全量载荷。

## 复现

先完成根 README 的 JDK 11、Rust 和 oracle 准备，然后串行执行：

```bash
./tools/evaluation/build.sh -B verify
./tools/evaluation/build.sh -B -Prust-jni clean verify
(cd rust && cargo fmt --check && cargo clippy --locked --workspace --all-targets -- -D warnings)
(cd rust && cargo test --locked --workspace)
(cd tools/evaluation && python3 -m unittest -v test_runner)

# 两候选 × 九策略；每策略四档命中率，各 Q=1M，N=10M。
python3 tools/evaluation/run.py verify --candidate all --synthetic-verify \
  --output benchmark-results/rust-nine-synthetic-verify

# 同一真实全量数据的独立 PyArrow oracle，校验原始行号和八字段。
python3 tools/evaluation/run.py verify --candidate all \
  --oracle benchmark-results/oracle/oracle.tsv \
  --output benchmark-results/rust-nine-real-verify

# 972 项：两候选 × 九策略 × 三批次 × 三并发 × 三路径 × 两模式。
python3 tools/evaluation/run.py benchmark --candidate all --algorithms-quick \
  --output benchmark-results/rust-nine-java-quick
python3 tools/evaluation/export.py benchmark-results/rust-nine-java-quick \
  doc/verification/rust-nine-java-quick
```

指定 `--algorithm eytzinger` 等 ID 可缩小范围；`--candidate rust-jni` 仅测 Rust。`--candidate all` 默认覆盖全部九策略，`--prefix` 仅在未显式选择算法时缩小为 `prefix16`。`--quick` 为 B=4,096/C=1、含解析入口的额外短预检，不等于 972 项矩阵。

单元测试覆盖稠密二级目录、/24 边界、空桶、跨桶长区间、无符号高位、四/八路各种尾部、空输入、空树/不满树、索引字节数及独立视图。公共 Java 契约对九种 Rust 策略分别执行边界、payload、解析、坏数据、offset、关闭及并发检查，Native 测试用 `-Xcheck:jni`。

本轮实际数据、自动化结果与限制见 [九策略配对快速报告](Rust九策略配对快速测量结果.md)。正式多 fork 长测、长期稳定性和生产采用判断留待后续。
