# Rust 与 Java 配对快速测量结果

日期：2026-11-04。状态：静态 Rust/JNI 原型、公共契约、真实全量核验及 **324/324 项配对快速矩阵已完成**；无缺项、额外项或重复项，资源保护未触发。

本轮限定 Linux x86_64 GNU libc、Java 11。Rust 支持 `binary`、`prefix16`、`prefix20`，Java 保留九种策略。默认仍为 `binary`。功能验证通过与性能采用结论分开：快速结果用于选择正式复测场景，尚不能确认生产收益。

## 环境、数据与测量方法

| 项目 | 本轮记录 |
|---|---|
| 测量源码 | `4cc85f7442343bdffa839bfda9807fd68437fab6` |
| 数据 | 32,307,681 行、324 个 Row Group |
| 数据 SHA-256 | `53defbfb94785be2fa064d42cf85ab26de8a8fa88bf9cd1d5c2c587a77f1b7da` |
| 查询输入 SHA-256 | `1845f9d5f0a0d320e5ef58c610949fa06c317951c7636a0067657d4b8c798b4f`，大端 IPv4 bit pattern |
| Java/JMH | JDK 11、JMH 1.37，Parquet Java 1.17.1 |
| Rust | rustc 1.99.1 (58cf8f9c3 2026-10-13)，Arrow/Parquet 59.2.0、jni 0.21.1；完整依赖以 Cargo.lock 为准 |
| Native 构建 | release、x86_64-unknown-linux-gnu，编译时 Rust flags 为空；Native hash 和完整编译元数据见证据 |
| CPU | Intel(R) Core(TM) 5 220H，16 个逻辑 CPU |
| 系统 | Linux-6.18.33.2-microsoft-standard-WSL2-x86_64-with-glibc2.39 |
| 主矩阵 | 两候选 × 三策略 × B=1,024/4,096/65,536 × C=1/4/8 × 三结果路径 × 吞吐/sample |
| 输入与消费 | seed=20261104、Q=1,000,000 输入池由线程分摊；真实数据合法 IPv4 命中率 100%，消费实际结果 |
| 快速参数 | forks=0，1×1 秒预热、1×1 秒测量；每个候选/算法独立进程，进程内场景共享 JIT/GC 历史 |

固定种子安排的进程顺序为：`java/prefix16 → rust-jni/prefix16 → rust-jni/binary → java/binary → java/prefix20 → rust-jni/prefix20`。进程串行，一次只驻留一套全量快照，按相同 B/C 和结果路径比较同轮 Java 与 Rust，不与旧 Java 九策略轮次混合计算收益比。

JMH 基准复用同一输入生成与结果消费。行号路径包括公共 API 查找与行号消费；UTF-8 路径包含八字段提取与字节消费；字符串路径包含转为 Java String 与逐字段消费。Rust 路径包含真实 JNI 句柄、输入复制、结果复制和适配成本。加载、索引构建与输入生成不计入查询时间，必要的运行期分配计入。

**本轮为探索性快速测量，没有独立 fork 误差估计。** 单进程内不同场景共享 JIT/GC 历史，候选间独立进程也不等于完成正式多 fork 长测；不据单个配置、短时间比值或 P99 宣称稳定优势。文件 page cache 未受控，加载记录不作为冷启动排名。

## 同配置吞吐对照

固定 **B=4,096、C=8**，单位为**百万 IP/s**，数值为所有线程总吞吐。

| 候选 | 策略 | 行号 | 八字段 UTF-8 | 八字段字符串 |
|---|---|---:|---:|---:|
| java | binary | 12.861 | 2.790 | 2.332 |
| rust-jni | binary | 15.183 | 2.428 | 2.240 |
| java | prefix16 | 38.060 | 3.147 | 2.723 |
| rust-jni | prefix16 | 47.141 | 2.699 | 2.757 |
| java | prefix20 | 53.360 | 2.819 | 2.290 |
| rust-jni | prefix20 | 72.181 | 3.621 | 2.771 |

同轮同配置的 Rust/JNI ÷ Java 吞吐比，仅用于展示观测幅度，不代表稳定加速比：

| 策略 | 行号 | 八字段 UTF-8 | 八字段字符串 |
|---|---:|---:|---:|
| binary | 1.18× | 0.87× | 0.96× |
| prefix16 | 1.24× | 0.86× | 1.01× |
| prefix20 | 1.35× | 1.28× | 1.21× |

行号结果与业务八字段结果分别比较，不能用行号收益替代字符串业务收益。不同结果路径的差值不能精确分解为 JNI 复制、UTF-8 提取或字符串物化耗时；本轮未运行对应成本控制组。

## 批次延迟与资源

同样固定 B=4,096、C=8，下表为**八字段字符串**的独立 sample 测量，单位为**毫秒/批次**。吞吐与 sample 分别运行。

| 候选 | 策略 | P50 ms | P95 ms | P99 ms | 样本数 | 尾延迟样本提示 |
|---|---|---:|---:|---:|---:|---|
| java | binary | 13.959 | 20.513 | 32.931 | 566 | 不足 |
| rust-jni | binary | 14.385 | 20.583 | 26.408 | 536 | 不足 |
| java | prefix16 | 11.723 | 16.787 | 28.506 | 696 | 不足 |
| rust-jni | prefix16 | 12.435 | 17.528 | 27.495 | 621 | 不足 |
| java | prefix20 | 11.600 | 16.237 | 17.689 | 716 | 不足 |
| rust-jni | prefix20 | 11.592 | 16.482 | 23.462 | 674 | 不足 |

少于 10,000 个批次样本时，P99 仅作提示；数量达标也不能替代独立长测。完整 324 项的分位数、样本数、JMH 误差字段、GC 指标和直方图均保留在证据中。Java allocation/GC 指标不包含 Rust Native 分配。

| 进程 | 加载 s | 进程总耗时 s | 采样峰值 RSS GiB | 最低 MemAvailable GiB |
|---|---:|---:|---:|---:|
| java-binary | 29.28 | 149.1 | 10.14 | 3.90 |
| java-prefix16 | 27.14 | 146.1 | 10.11 | 3.95 |
| java-prefix20 | 31.78 | 150.1 | 10.17 | 3.81 |
| rust-jni-binary | 9.98 | 129.1 | 6.73 | 7.23 |
| rust-jni-prefix16 | 10.39 | 128.1 | 6.62 | 7.31 |
| rust-jni-prefix20 | 10.41 | 128.1 | 6.69 | 7.35 |

六个进程合计约 830.7 秒（13.8 分钟），最高采样 RSS 10.17 GiB，最低系统可用内存 3.81 GiB。RSS 每秒采样，不能当作精确瞬时峰值或存活分配；CPU 采样完整保存在压缩证据内。加载耗时包含文件 hash、解码、校验及所选索引构建，额外策略视图的构建时间另行记录。

前缀目录采用与 Java 相同的 32 位行偏移：`prefix16` 额外 262,148 字节，`prefix20` 额外 4,194,308 字节；该口径只含目录数组，不是 RSS。原生内存和 Java 堆由不同分配器管理，本轮未完成 Native allocator 存活分配/留存诊断，不按采样 RSS 推导精确回收量。

## 正确性与自动化证据

- 默认 Java 构建与 59 项 Java 契约测试通过；默认构建无需 Rust。
- Native profile 的 77 项共用契约和 3 项 JNI 边界测试通过，启用 `-Xcheck:jni`；包括加载失败、ABI 错配、失效句柄、百万批次、独立结果及 100 次 fixture 加载/关闭。
- Rust 3 项 core 测试与 1 项生命周期测试通过，覆盖线性 oracle、前缀长区间、坏数据、屏障控制关闭竞争、100 次生命周期循环和受控 unwind 后引用清理；Clippy 无警告。
- 14 项运行器测试通过，覆盖候选能力、矩阵覆盖、失败过滤及源码/Native/输入等续跑身份隔离。
- 两候选三策略均通过 N=10M/Q=1M、0%/50%/90%/100% 命中率算术 oracle，共 24 个百万查询工作单元；八字段抽查同时通过。
- 两候选三策略均匹配真实全量数据上 5,171 个独立 PyArrow 样本的原始行号与八字段，数据 hash 与原 oracle 匹配。
- 主矩阵前的两候选三策略预检为 48/48 项，通过行号、UTF-8、字符串和严格字符串解析入口。
- 源码提交的远端 [Java 与 Rust/JNI CI](https://github.com/LouisLiuNova/IPloc-benchmark/actions/runs/41793786546) 均成功；CI 仅覆盖构建和小数据，不代表在远端执行了全量矩阵。

证据文件：[324 项指标摘要](verification/rust-java-quick.json)、[原始 JMH 指标/直方图与资源采样](verification/rust-java-quick-raw.json.gz)、[正确性与预检记录](verification/rust-jni-validation.json)。摘要包含源码/JAR/Native/数据/查询 hash、实际运行顺序、覆盖检查及编译元数据。原始大数据、`.so` 和大型控制台日志不纳入 Git。

## 复跑、结论与后续

构建、验证和测量命令见 [Rust 接入说明](../rust/README.md)。本轮本地目录为 `benchmark-results/rust-synthetic-verify`、`rust-real-verify`、`rust-preflight` 和 `rust-java-quick`；每个运行保留 manifest、完整命令、控制台、GC 日志、状态与资源采样。使用新目录重跑；只有制品与输入身份完全一致时才允许 `--resume`。

本轮已完成功能和快速比较，性能采用判断仍为**证据不足**：没有业务 SLO、正式多 fork 长测与长期稳定性结果，保持默认 `binary`，不据本轮数据将 Java 或 Rust 宣布为普遍赢家。后续应围绕实际使用的八字段 UTF-8/字符串路径选择有限配置正式复测，继续采用同机、同数据、同算法、独立进程配对方法。

Rust 的 `prefix16-24`、`interleaved4/8`、`prefix16-interleaved4/8`、`eytzinger` 仍待接入。热刷新、C ABI、跨平台 classifier JAR、零拷贝、内部线程池、长期稳定性与生产上线不在本轮交付范围。
