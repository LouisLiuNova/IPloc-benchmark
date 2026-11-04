# Rust 九策略配对快速测量结果

日期：2026-11-04。状态：Rust 剩余六策略已实现，两候选九策略均通过真实全量独立 oracle 与大规模合成核验，**972/972 项配对快速矩阵完成**，无缺项、额外项、重复项或资源保护触发。默认仍为 `binary`。

本轮以主分支 `244786c` 为基础，测量源码为 `7c753aa8f298eb51f1d9c759c7cd640d4461646f`。新增 `prefix16-24`、`interleaved4/8`、`prefix16-interleaved4/8`、`eytzinger`，实现语义、索引结构和复跑入口见 [Rust 搜索策略](Rust搜索策略.md)。[首轮三策略报告](Rust与Java配对快速测量结果.md)保留为历史记录，不与本轮混算比值。

## 环境与测量口径

| 项目 | 本轮配置 |
|---|---|
| 数据 | 32,307,681 行、324 个 Parquet Row Group |
| 数据 SHA-256 | `53defbfb94785be2fa064d42cf85ab26de8a8fa88bf9cd1d5c2c587a77f1b7da` |
| 查询输入 SHA-256 | `1845f9d5f0a0d320e5ef58c610949fa06c317951c7636a0067657d4b8c798b4f` |
| 工具 | JDK 11、JMH 1.37、Parquet Java 1.17.1；Rust 1.99.1、Arrow/Parquet 59.2.0、jni 0.21.1 |
| 平台 | Intel(R) Core(TM) 5 220H，16 个逻辑 CPU；Linux x86_64 GNU libc、WSL2，约 15.37 GiB 内存 |
| Native | release、标准 x86_64-unknown-linux-gnu 目标，未设置 target-cpu=native；编译元数据及制品 hash 见摘要 |
| 矩阵 | 2 候选 × 9 策略 × B=1,024/4,096/65,536 × C=1/4/8 × 行号/UTF-8/字符串 × 吞吐/sample |
| 输入与消费 | seed=20261104，Q=1M 输入池由线程分摊；真实数据命中率 100%，消费实际结果 |
| 快速参数 | 每候选/算法独立进程，串行交替；forks=0，1×1 秒预热、1×1 秒测量 |

B 为单次调用 IP 数，C 为外部 Java 线程数。所有路径均通过公共 Java API，Rust 包含 JNI 句柄、输入复制、结果复制与 Java 适配。行号路径消费行号，UTF-8 路径提取八字段并消费字节，字符串路径物化 Java String 并逐字段消费。加载、索引构建、输入生成在查询计时之外。

候选与算法进程顺序按固定种子生成：`java/prefix16-interleaved4 → rust-jni/prefix16-interleaved4 → rust-jni/prefix16 → java/prefix16 → java/prefix16-interleaved8 → rust-jni/prefix16-interleaved8 → rust-jni/binary → java/binary → java/interleaved4 → rust-jni/interleaved4 → rust-jni/eytzinger → java/eytzinger → java/prefix20 → rust-jni/prefix20 → rust-jni/interleaved8 → java/interleaved8 → java/prefix16-24 → rust-jni/prefix16-24`。

**本轮属于探索性短测，单进程内共享 JIT/GC 历史，没有正式多 fork 误差估计。** 不把短测比值或 P99 当作生产 SLO，不将不同结果路径的差值解释为某阶段的精确成本。

## 同配置吞吐

固定 B=4,096、C=8，单位为**百万 IP/s**，为所有调用线程总吞吐。

| 候选 | 策略 | 行号 | 八字段 UTF-8 | 八字段字符串 |
|---|---|---|---|---|
| java | binary | 12.991 | 2.043 | 2.264 |
| rust-jni | binary | 14.008 | 2.612 | 2.166 |
| java | prefix16 | 38.760 | 3.436 | 2.478 |
| rust-jni | prefix16 | 42.995 | 3.327 | 2.620 |
| java | prefix20 | 40.454 | 3.346 | 2.701 |
| rust-jni | prefix20 | 69.899 | 3.597 | 2.771 |
| java | prefix16-24 | 40.210 | 3.588 | 2.943 |
| rust-jni | prefix16-24 | 39.458 | 3.327 | 2.333 |
| java | interleaved4 | 19.987 | 3.175 | 2.477 |
| rust-jni | interleaved4 | 19.824 | 2.935 | 2.081 |
| java | interleaved8 | 18.507 | 2.933 | 2.379 |
| rust-jni | interleaved8 | 23.456 | 2.856 | 2.615 |
| java | prefix16-interleaved4 | 40.304 | 3.347 | 2.611 |
| rust-jni | prefix16-interleaved4 | 44.699 | 2.815 | 2.492 |
| java | prefix16-interleaved8 | 32.910 | 3.392 | 2.589 |
| rust-jni | prefix16-interleaved8 | 40.844 | 3.471 | 2.853 |
| java | eytzinger | 17.342 | 3.280 | 2.496 |
| rust-jni | eytzinger | 15.389 | 2.741 | 2.327 |

同轮 Rust/JNI ÷ Java 的吞吐比：

| 策略 | 行号 | 八字段 UTF-8 | 八字段字符串 |
|---|---|---|---|
| binary | 1.08× | 1.28× | 0.96× |
| prefix16 | 1.11× | 0.97× | 1.06× |
| prefix20 | 1.73× | 1.08× | 1.03× |
| prefix16-24 | 0.98× | 0.93× | 0.79× |
| interleaved4 | 0.99× | 0.92× | 0.84× |
| interleaved8 | 1.27× | 0.97× | 1.10× |
| prefix16-interleaved4 | 1.11× | 0.84× | 0.95× |
| prefix16-interleaved8 | 1.24× | 1.02× | 1.10× |
| eytzinger | 0.89× | 0.84× | 0.93× |

同配置下的主要观察：

- **Rust 行号优先复测 `prefix20`**：约 69.90 百万 IP/s，为同算法 Java 的 1.73 倍；Rust 内部也高于其他八策略，但这一收益不能套用到八字段结果路径。
- **Rust 字符串优先联合复测 `prefix16-interleaved8` 与 `prefix20`**：约 2.853/2.771 百万 IP/s，相差约 3%；前者只需约 256 KiB 的目录。短测不足以判定稳定胜负。
- **相同语言内的最佳策略仍需对照**：该配置下，Rust UTF-8 最高为 `prefix20` 的 3.597 百万 IP/s，接近 Java `prefix16-24` 的 3.588；Rust 字符串最高约 2.853，略低于 Java `prefix16-24` 的 2.943。不能只选 Java 基础二分作为业务性能对照。
- **4/8 路交错分别保留**：Rust 纯八路行号/字符串约 23.46/2.615，高于纯四路的 19.82/2.081；前缀四路的行号高于前缀八路，而前缀八路的两条八字段路径更高。未显示统一最佳路数。
- **新增两级目录与 Eytzinger 未显示配对优势**：两者在该配置下的 Rust 三路径均低于同算法 Java。两级目录比 `prefix20` 占用更少数组字节，Eytzinger 需要约 246.5 MiB；不能只按行号相对 `binary` 的提升判断价值。

## 批次与并发观察

以下均为 Rust/JNI，总吞吐单位为百万 IP/s。固定 B=4,096 比较并发，再固定 C=8 比较批次；每种策略最后两行与其 B=4,096/C=8 行对照。

| 策略 | B | C | 行号 | 八字段 UTF-8 | 八字段字符串 |
|---|---:|---:|---:|---:|---:|
| prefix20 | 4,096 | 1 | 12.647 | 0.463 | 0.453 |
| prefix20 | 4,096 | 4 | 40.072 | 1.813 | 1.372 |
| prefix20 | 4,096 | 8 | 69.899 | 3.597 | 2.771 |
| prefix20 | 1,024 | 8 | 60.070 | 3.510 | 2.515 |
| prefix20 | 65,536 | 8 | 65.661 | 3.751 | 2.453 |
| prefix16-interleaved8 | 4,096 | 1 | 9.970 | 0.759 | 0.410 |
| prefix16-interleaved8 | 4,096 | 4 | 31.095 | 2.270 | 1.863 |
| prefix16-interleaved8 | 4,096 | 8 | 40.844 | 3.471 | 2.853 |
| prefix16-interleaved8 | 1,024 | 8 | 51.828 | 3.447 | 2.739 |
| prefix16-interleaved8 | 65,536 | 8 | 42.093 | 3.523 | 2.809 |
| prefix16-24 | 4,096 | 1 | 7.237 | 0.702 | 0.476 |
| prefix16-24 | 4,096 | 4 | 25.443 | 1.976 | 0.985 |
| prefix16-24 | 4,096 | 8 | 39.458 | 3.327 | 2.333 |
| prefix16-24 | 1,024 | 8 | 37.312 | 3.029 | 2.523 |
| prefix16-24 | 65,536 | 8 | 40.743 | 3.521 | 2.162 |

不同批次、并发及结果路径的较优策略不同，本轮不确定统一最佳批次或稳定并发扩展比。全部 972 项保留在指标摘要，正式复测应缩小到实际业务关注的路径与配置。

## 批次延迟

固定 B=4,096、C=8，八字段字符串的独立 sample 测量，单位为**毫秒/批次**。

| 候选 | 策略 | P50 ms | P95 ms | P99 ms | 样本数 |
|---|---|---|---|---|---|
| java | binary | 13.812 | 21.463 | 22.898 | 573 |
| rust-jni | binary | 14.213 | 20.759 | 27.682 | 544 |
| java | prefix16 | 11.305 | 17.339 | 22.811 | 696 |
| rust-jni | prefix16 | 11.665 | 17.066 | 25.589 | 671 |
| java | prefix20 | 11.854 | 19.759 | 22.784 | 654 |
| rust-jni | prefix20 | 11.207 | 15.879 | 23.892 | 703 |
| java | prefix16-24 | 11.846 | 17.727 | 23.524 | 678 |
| rust-jni | prefix16-24 | 11.960 | 16.980 | 24.872 | 657 |
| java | interleaved4 | 12.435 | 18.350 | 25.588 | 647 |
| rust-jni | interleaved4 | 14.795 | 22.698 | 23.945 | 525 |
| java | interleaved8 | 13.386 | 21.078 | 23.072 | 594 |
| rust-jni | interleaved8 | 12.370 | 17.320 | 18.138 | 648 |
| java | prefix16-interleaved4 | 11.387 | 17.675 | 21.814 | 695 |
| rust-jni | prefix16-interleaved4 | 14.287 | 19.127 | 27.717 | 542 |
| java | prefix16-interleaved8 | 13.140 | 19.205 | 20.843 | 597 |
| rust-jni | prefix16-interleaved8 | 11.305 | 16.335 | 24.844 | 693 |
| java | eytzinger | 15.368 | 26.868 | 33.577 | 486 |
| rust-jni | eytzinger | 15.860 | 20.664 | 34.037 | 496 |

吞吐与 sample 分别运行，不能直接取倒数对应。少于 10,000 批次的 P99 仅作提示；样本量达标也不替代正式长测。全部路径的分位数、样本数、JMH 误差与原始直方图见压缩证据。

## 索引、加载与资源

| 策略 | 额外基本类型数组字节（两候选相同） | Java 构建 ms | Rust 构建 ms |
|---|---|---|---|
| binary | 0 | 9.06 | 6.40 |
| prefix16 | 262148 | 42.13 | 19.12 |
| prefix20 | 4194308 | 42.80 | 27.63 |
| prefix16-24 | 2004608 | 64.43 | 28.49 |
| interleaved4 | 0 | 5.21 | 7.89 |
| interleaved8 | 0 | 5.41 | 5.66 |
| prefix16-interleaved4 | 262148 | 29.19 | 23.08 |
| prefix16-interleaved8 | 262148 | 36.28 | 22.86 |
| eytzinger | 258461456 | 157.31 | 291.30 |

真实数据中两级目录的密集 /16 桶数为 1,695，Java 与 Rust 一致，目录合计 2,004,608 字节。

额外字节数只含基本类型数组，不含数据、容器、二级目录指针、分配器与临时分配，不等于 RSS。构建时间为加载后另建一个共享数据的策略视图的一次观测；文件 page cache 与进程运行历史未受控，不作为稳定加载或构建排名。

| 候选 | 加载范围 s | 进程耗时合计 s | 采样峰值 RSS 范围 GiB | 最低系统可用内存 GiB |
|---|---|---|---|---|
| java | 26.83–30.40 | 1330.2 | 9.88–10.33 | 3.65 |
| rust-jni | 8.72–10.20 | 1152.1 | 6.38–9.09 | 4.91 |

主矩阵各进程合计 2482.3 秒，约 41.4 分钟，最高采样 RSS 10.33 GiB，最低系统可用内存 3.65 GiB。每秒采样，保留 2 GiB 系统余量/12 GiB 进程组 RSS 保护，未触发终止。Java GC 与分配指标不包含 Native 分配；不据 RSS 推导精确回收量。完整逐进程资源数据见摘要与压缩证据。

## 验证与证据

- 默认 Java 构建及 59 项 Java 契约/边界测试通过；默认构建无需 Rust。
- Native profile 干净构建及 113 项共用契约通过，其中 54 项覆盖 Rust 九策略；另有 3 项 JNI 边界测试通过，启用 `-Xcheck:jni`，100 次加载/关闭循环覆盖全部策略。
- Rust 4 项 core 与 1 项生命周期测试通过；`cargo fmt --check`、Clippy `-D warnings` 通过。新增密集二级目录、跨桶长区间、交错尾部和原始行号恢复验证。
- Python 运行器 14 项测试通过，覆盖 972 项矩阵能力与覆盖统计、失败过滤、续跑身份核对和显式策略选择。
- 两候选九策略共 18 个组合，各自通过真实全量数据上 5,171 个独立 PyArrow 样本的原始行号和八字段核验。
- 两候选九策略各自在 N=10M 数据上完成四档命中率 × Q=1M 的算术 oracle，共 72 个百万查询工作单元，八字段抽查通过。
- 真实路径压力由 32M 全量快照和 Q=1M 输入池、最高 B=65,536/C=8 的独立吞吐与 sample 测量覆盖。合成命中率仅验证正确性，本轮未运行合成命中率性能矩阵。
- 本轮未额外执行 `--quick` 解析性能预检；解析正确性由公共契约覆盖。未运行正式多 fork 长测、长期稳定性或远端 CI，不宣称发布完成。

证据：[972 项指标摘要](verification/rust-nine-java-quick.json)、[原始 JMH 指标/直方图与资源采样](verification/rust-nine-java-quick-raw.json.gz)、[正确性核验记录](verification/rust-nine-validation.json)。保留源码、JAR、Native、数据、查询、工具链与 Cargo.lock hash，完整覆盖检查和运行顺序。

复跑命令见 [Rust 搜索策略](Rust搜索策略.md)及 [Rust 接入说明](../rust/README.md)。本机日志位于 `benchmark-results/rust-nine-synthetic-verify`、`rust-nine-real-verify`、`rust-nine-java-quick`，不纳入 Git；使用新目录复跑，只有制品与输入身份一致时才使用 `--resume`。

默认保持 `binary`。后续围绕实际八字段结果路径做有限配置的正式独立 fork 复测；热刷新、C ABI、跨平台分发、零拷贝、内部线程池与生产采用不在本次范围。
