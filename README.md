# IPloc-benchmark

面向 Java 11 的 IPv4 区间批量查询库与性能评估框架，并列维护 `java`、`rust-jni` 候选。Java 与 Linux x86_64 GNU libc 的 Rust/JNI 候选均支持九种搜索策略，通过相同 Java 公共契约。

当前默认搜索算法为 `binary`。本项目尚未完成正式独立 fork 长测，现有快速结果用于筛选后续复测配置；Rust/JNI 九策略的配对实测与限制见[中文报告](doc/Rust九策略配对快速测量结果.md)。

- [Rust/JNI 配对快速结果](#rustjni-配对快速结果)
- [Java 实测数据与结论](#java-实测数据与结论)
- [构建与正确性测试](#构建与正确性测试)
- [使用方式与接口约束](#使用方式与接口约束)
- [复现实测与资源保护](#复现实测与资源保护)
- [工程与文档导航](#工程与文档导航)

## Rust/JNI 配对快速结果

Rust 已补齐 `prefix16-24`、`interleaved4/8`、`prefix16-interleaved4/8`、`eytzinger`，两候选现均支持九种策略。两侧九策略均通过 5,171 个真实全量独立 oracle 样本，以及 N=10M/Q=1M、四档命中率合成核验。**同轮 972/972 项配对快速矩阵完成**，无缺项、重复项或资源保护触发。默认 Java 构建无需 Rust，Native 通过 `-Prust-jni` 显式启用。

下表固定 B=4,096、C=8，吞吐单位为**百万 IP/s**，包含相应结果消费。最后一列为同轮、同算法、同配置的字符串吞吐比；完整 Java/Rust 三路径对照见[九策略报告](doc/Rust九策略配对快速测量结果.md)。

| Rust 策略 | 行号 | 八字段 UTF-8 | 八字段字符串 | 字符串 Rust ÷ Java |
|---|---|---|---|---|
| `binary` | 14.008 | 2.612 | 2.166 | 0.96× |
| `prefix16` | 42.995 | 3.327 | 2.620 | 1.06× |
| `prefix20` | 69.899 | 3.597 | 2.771 | 1.03× |
| `prefix16-24` | 39.458 | 3.327 | 2.333 | 0.79× |
| `interleaved4` | 19.824 | 2.935 | 2.081 | 0.84× |
| `interleaved8` | 23.456 | 2.856 | 2.615 | 1.10× |
| `prefix16-interleaved4` | 44.699 | 2.815 | 2.492 | 0.95× |
| `prefix16-interleaved8` | 40.844 | 3.471 | 2.853 | 1.10× |
| `eytzinger` | 15.389 | 2.741 | 2.327 | 0.93× |

本轮观测与验证结果：

- **行号路径优先复测 `prefix20`**：Rust 约 69.90 百万 IP/s，为同轮 Java 的 1.73 倍；UTF-8/字符串约为同算法 Java 的 1.08/1.03 倍，业务结果收益远小于行号收益。
- **新增策略中，Rust 字符串优先复测 `prefix16-interleaved8`**：该配置约 2.85 百万 IP/s，为同算法 Java 的 1.10 倍，也是该配置下 Rust 九策略中最高值；Java `prefix16-24` 约 2.94 百万 IP/s，不能据此宣称 Rust 整体更快。
- **策略组合需要实测选择**：Rust 纯八路交错的行号和字符串高于纯四路；Rust 两级目录的三路径均低于同算法 Java，Eytzinger 也未显示配对优势。Eytzinger 额外数组约 246.5 MiB，两级目录约 1.91 MiB，前者并未在该配置中抵消额外成本。
- 默认 Java **59 项**测试、Native profile **113 项**共用契约及 **3 项** JNI 边界测试、Rust **5 项**单元测试、Python **14 项**运行器测试通过。两候选九策略合成 oracle 共核验 **7,200 万次查询**；默认仍为 `binary`。
- 主矩阵约 **41.4 分钟**，最高采样进程组 RSS **10.33 GiB**，最低系统可用内存 **3.65 GiB**。RSS 每秒采样，Java GC 指标不包含 Native 分配。
- 可复核证据：[972 项指标摘要](doc/verification/rust-nine-java-quick.json)、[原始指标、直方图与资源采样](doc/verification/rust-nine-java-quick-raw.json.gz)、[正确性核验记录](doc/verification/rust-nine-validation.json)。保留源码、JAR、Native、数据及查询输入的 hash。

每个候选/算法使用独立进程，进程内 forks=0、1×1 秒预热和测量，共享 JIT/GC 历史；仍属探索性短测，尚未完成正式多 fork 长测。本轮未额外重复解析性能预检，解析正确性由公共契约覆盖。完整延迟、加载/资源、限制和复跑方法见[九策略报告](doc/Rust九策略配对快速测量结果.md)、[策略说明](doc/Rust搜索策略.md)与 [Rust 接入说明](rust/README.md)。[首轮三策略结果](doc/Rust与Java配对快速测量结果.md)及以下 Java 九策略结果保留为历史记录，不与本轮混算比值。

## Java 实测数据与结论

2026-11-04 的前一轮 Java 九策略评估完成 **486/486 个测量组合**，无缺项、额外项或重复项；资源保护未触发。JDK 11 构建、59 项契约/边界测试及 10 项运行器测试通过，9 个策略各自通过真实全量数据上 **5,171 个独立 PyArrow oracle 样本**的原始行号与八字段比对。

### 环境与测量口径

| 项目 | 本轮配置 |
|---|---|
| 真实数据 | 32,307,681 行，324 个 Parquet Row Group |
| 数据 SHA-256 | `53defbfb94785be2fa064d42cf85ab26de8a8fa88bf9cd1d5c2c587a77f1b7da` |
| 本机环境 | Intel Core 5 220H，16 个逻辑 CPU；WSL2，约 15.37 GiB 内存 |
| 运行版本 | JDK 11.0.32.1、Maven 3.9.11、Parquet Java 1.17.1、JMH 1.37 |
| 矩阵 | 9 策略 × B=1,024/4,096/65,536 × C=1/4/8 × 行号/八字段 UTF-8/八字段字符串 × 吞吐/批次延迟 |
| 输入与消费 | 固定种子 20261104，Q=1,000,000 输入池由线程分摊，真实数据 100% 命中，所有路径消费实际结果 |
| 快速测量 | JMH forks=0，1×1 秒预热、1×1 秒测量；按固定种子打乱策略顺序，串行运行 |
| 测量源码 | `db5810f504687adde18a109a7bc546986137997e`；JAR hash 见[指标摘要](doc/verification/java-algorithms-quick.json) |

B 表示每次调用处理的 IP 数量，C 表示外部调用线程数。数据加载、索引构建和输入生成在查询计时之外。UTF-8 路径包含八字段提取与字节消费；字符串路径包含转换为 Java String 与逐字段消费。

**本轮为探索性快速测量，各策略共享 JIT/GC 历史，没有独立 fork 的误差估计。** JMH 对 non-forked 模式有调试用途警告；以下观测不能作为生产 SLO 或稳定最优配置。不同路径的消费逻辑不同，路径之间的差值不能直接解释为某个阶段的精确耗时。

### 九策略吞吐对照

固定 **B=4,096、C=8**，单位为**百万 IP/s**，数值为所有线程的总吞吐。

| 策略 | 行号 | 八字段 UTF-8 | 八字段字符串 |
|---|---:|---:|---:|
| `binary` | 13.873 | 2.949 | 2.117 |
| `prefix16` | 36.865 | 3.639 | 2.810 |
| `prefix20` | 63.843 | 3.907 | 2.796 |
| `prefix16-24` | 41.742 | 3.689 | 2.966 |
| `interleaved4` | 20.772 | 3.379 | 2.726 |
| `interleaved8` | 20.549 | 3.194 | 2.698 |
| `prefix16-interleaved4` | 47.732 | 3.252 | 2.816 |
| `prefix16-interleaved8` | 43.047 | 3.664 | 3.011 |
| `eytzinger` | 17.134 | 2.687 | 2.405 |

本轮结论与后续复测选择：

- **行号路径优先复测 `prefix20`**：该配置约 63.84 百万 IP/s；八字段 UTF-8 约 3.91 百万 IP/s，业务结果路径的收益幅度较小，不能直接套用行号加速比。
- **字符串路径联合复测 `prefix16-interleaved8` 与 `prefix16-24`**：该配置约 3.01 / 2.97 百万 IP/s，差距很小，暂不判断稳定胜负。
- **4/8 路交错需要分别保留对照**：纯交错行号约 20.77 / 20.55 百万 IP/s，本轮未显示路数越多越快。
- **Eytzinger 未显示全面优势**：该配置行号高于基础二分，UTF-8 低于基础二分，额外索引约 246.5 MiB。`prefix20` 的额外基本类型数组约 4 MiB；该口径不含对象头、引用、临时内存，也不等于 RSS。
- **默认维持 `binary`**：围绕实际业务需要的 UTF-8/字符串结果做正式独立 fork 复测后，再决定是否切换策略。

### 批次与并发观察

以下以 `prefix20` 展示业务结果路径，单位为百万 IP/s。前 3 行固定批次观察并发，后 2 行与 C=8、B=4,096 对照批次变化；[完整报告](doc/Java搜索算法快速测量结果.md)保留其他策略的批次数据。

| B | C | 八字段 UTF-8 | 八字段字符串 |
|---:|---:|---:|---:|
| 4,096 | 1 | 0.717 | 0.571 |
| 4,096 | 4 | 2.968 | 2.207 |
| 4,096 | 8 | 3.907 | 2.796 |
| 1,024 | 8 | 3.878 | 3.047 |
| 65,536 | 8 | 3.345 | 2.647 |

固定 B=4,096，C 从 1 增至 8 时，`prefix20` 的 UTF-8 与字符串吞吐分别约增至 5.45 倍、4.90 倍，未达到线性扩展。C=8 时，B=1,024/4,096 的业务吞吐均高于 B=65,536，适合优先复测；不同路径与策略下的较优批次不同，本轮不能确定统一最佳批次。

### 批次延迟、加载与资源

以下为同轮 `prefix20`、B=4,096、C=8 的独立 sample 模式观测，单位为**毫秒/批次**；吞吐与延迟分别运行。

| 路径 | P50 ms | P95 ms | P99 ms | 样本数 |
|---|---:|---:|---:|---:|
| 行号 | 0.574 | 0.851 | 1.083 | 13,837 |
| 八字段 UTF-8 | 8.503 | 13.566 | 15.008 | 924 |
| 八字段字符串 | 9.667 | 16.531 | 20.840 | 759 |

八字段路径样本均不足 10,000，**P99 不作为稳定尾延迟证据**；行号样本数量达到该提示门槛，也不替代独立长测。完整误差、直方图及 GC 指标保存在原始记录中。

| 资源观测 | 本轮数值 |
|---|---:|
| 完整快速矩阵耗时 | 1,077.9 秒，约 18 分钟 |
| Parquet 加载耗时 | 27.7 秒，仅加载一次全量快照 |
| 最高采样进程组 RSS | 10.37 GiB |
| 最低系统 MemAvailable | 3.36 GiB |
| 评估 JVM 最大堆 | 10 GiB |

RSS 为采样峰值，不是精确瞬时最大值。资源数据用于确认本机余量与解释性能，不设资源效率排名。

### 证据与尚未完成项

- **最新算法对照**：[完整中文分析](doc/Java搜索算法快速测量结果.md)、[486 项指标摘要](doc/verification/java-algorithms-quick.json)、[压缩原始指标、直方图与资源采样](doc/verification/java-algorithms-quick-raw.json.gz)，对应 [PR #2](https://github.com/LouisLiuNova/IPloc-benchmark/pull/2)。
- **首轮历史结果**：[128 项快速矩阵](doc/Java全矩阵快速测量结果.md)，包含基础二分的补充批次、合成空洞命中率及字符串解析入口，对应 [PR #1](https://github.com/LouisLiuNova/IPloc-benchmark/pull/1)。新旧两轮源码不同，不直接混用绝对值；最新九策略性能仅覆盖真实数据，未重复合成命中率性能矩阵。
- **尚未完成**：3 forks、5×1 秒预热、10×1 秒测量的正式长测，及跨候选正式长测。原正式运行已按先完成快速矩阵的决策停止，未计为完整正式成绩。

## 构建与正确性测试

使用 JDK 11，评估脚本需要 Python 3.11+。Maven Wrapper 固定为 3.9.11 并校验分发包 SHA-256，构建固定 `--release 11`。Linux CI 分别运行 Java 与 Rust/JNI 的构建与小数据校验，真实全量核验和性能矩阵在本机执行。

```bash
# 可用 IPLOC_JDK_HOME 指向其他机器的 JDK 11。
./tools/evaluation/build.sh verify
(cd tools/evaluation && python3 -m unittest -v test_runner)
```

默认 Wrapper 与依赖缓存位于 `/tmp`，可通过 `IPLOC_MAVEN_HOME`、`IPLOC_MAVEN_REPO` 调整。构建与运行均使用 JDK 11。

## 使用方式与接口约束

```java
try (IpRangeSnapshot snapshot = JavaIpRangeSnapshot.open(Path.of("iploc.parquet"))) {
    int[] ips = {0x01020304, 0xFFFFFFFF}; // int 按无符号 IPv4 bit pattern 解释
    long[] rows = new long[ips.length];
    snapshot.lookupBatch(ips, 0, ips.length, rows, 0);
    PayloadBatch payload = snapshot.fetchBatch(rows, 0, rows.length);
    String[][] strings = payload.toStrings();
    Map<String, String> client = payload.businessRow(0, true);
    Map<String, String> server = payload.businessRow(1, false);
    StringLookup parsed = snapshot.lookupStrings(new String[]{"1.2.3.4", "bad"}, 0, 2);
}
```

`open` 直接读取单个不可变 Parquet 文件，仅投影 `start_ip_int/end_ip_int` 与 `country/province/scene/isp/owner/areacode/user/asnumber`。边界必须为无逻辑注解的 INT64，载荷为 UTF-8 BINARY；重复字段、嵌套和不支持类型拒绝。边界非空且位于 IPv4 范围，闭区间严格有序、不重叠；允许空数据和空洞。加载前后检查文件 hash，版本为 SHA-256。调用方须保证加载期间文件不可变。

行号从 0 开始，`-1` 表示未命中，且只属于产生它的快照。批次最多 1,000,000，offset/length 必须有效，异常后不能消费本次输出。输入与输出区间不得并发修改。连续边界数组限制总行数小于 `Integer.MAX_VALUE - 8`；超出 Java 数组容量的载荷拒绝。`PayloadBatch` 的结果数组归调用方所有；null、空字符串和未命中分别表示，快照关闭不影响已返回结果。

快照可供多线程只读共享，关闭幂等；已取得内部状态的单次查询可完成，关闭后新调用失败。字符串便利入口包括解析、查找和提取多个步骤，关闭竞争可能使其抛异常。堆数据由 GC 回收，`close()` 不承诺 RSS 立即下降。无热刷新、内部线程池或 IPv6。

### 可选搜索策略

支持 `binary`、`prefix16`、`prefix20`、`prefix16-24`、`interleaved4`、`interleaved8`、`prefix16-interleaved4`、`prefix16-interleaved8`、`eytzinger`。使用 `open(path, SearchAlgorithm.PREFIX_20)` 或 `base.withAlgorithm(SearchAlgorithm.EYTZINGER)` 选择，旧接口和默认二分保持兼容。

策略视图共享不可变边界与载荷，各自持有索引和关闭状态，行号仍对应原始数据；关闭 base 不影响已创建的 view。两级目录、交错二分、Eytzinger 的实现边界及索引成本见 [Java 搜索算法候选](doc/Java搜索算法候选.md)。

## 复现实测与资源保护

原始 `iploc.parquet` 放在根目录，文件和大型运行日志不纳入 Git。只有 oracle 和 fixture 生成需要额外 Python 依赖；更换数据后应重新生成 oracle，并核对数据 hash。

```bash
uv venv .venv
uv pip install --python .venv/bin/python -r tools/evaluation/requirements.txt
.venv/bin/python tools/evaluation/oracle.py iploc.parquet benchmark-results/oracle

# 一次加载，逐策略核验全部 9 个策略的原始行号与八字段。
python3 tools/evaluation/run.py verify --verify-algorithms \
  --oracle benchmark-results/oracle/oracle.tsv --output benchmark-results/algorithms-verify

# 独立 fork 短预检：确认加载与消费，不作为正式性能结论。
python3 tools/evaluation/run.py benchmark --quick --candidate all \
  --output benchmark-results/preflight

# 最新九策略完整快速矩阵：486 项，JMH forks=0，共享 JIT/GC 历史。
python3 tools/evaluation/run.py benchmark --algorithms-quick \
  --output benchmark-results/algorithms-quick
python3 tools/evaluation/run.py report --output benchmark-results/algorithms-quick

# 正式复测示例：仅 prefix20、B=4,096、C=1，含三条结果路径与两种模式。
# 默认 3 forks、5×1 秒预热、10×1 秒测量；指定场景不等于完整正式矩阵。
python3 tools/evaluation/run.py benchmark --algorithm prefix20 --only real-b4096-c1 \
  --output benchmark-results/prefix20-formal
python3 tools/evaluation/run.py report --output benchmark-results/prefix20-formal
```

使用 `--jdk` 指定评估 JDK；`--resume` 仅跳过同目录已通过场景，要求源码提交、JAR、数据及 JDK 一致。启用 Native 构建后，`--candidate all` 比较全部九种共同策略，`--candidate rust-jni` 仅测 Rust；Native 缺失或显式选择未知策略会报错。跨候选使用 `--quick`、`--algorithms-quick` 或 `verify`，详见 [Rust 接入说明](rust/README.md)。原有 `benchmark --matrix-quick` 为首轮 128 项矩阵，不能替代新的九策略矩阵。

每个运行目录保存 manifest、命令、控制台、JMH JSON、GC 日志、进程组 RSS/CPU、系统可用内存采样、状态及汇总报告。正式测量的完整配置与参数见[评估说明](doc/Java候选与性能评估.md)及[算法测试入口](doc/Java搜索算法候选.md)。

运行器启动前检查可用内存，运行中每秒采样；连续两次 `MemAvailable < 2 GiB` 或进程组 RSS `> 12 GiB`，即终止当前进程组并停止后续场景。评估 fork 固定 `-Xmx10g`，场景、候选与 fork 串行，一次只驻留一套全量数据。测量错误、OOM、fork 异常或资源终止均保留记录，不自动无限重试，不调整系统 swap 或内核配置。

## 工程与文档导航

| 路径 | 职责 |
|---|---|
| `java/iprange-api` | 快照接口、八字段 UTF-8 结果、严格 IPv4 解析及业务字段适配 |
| `java/iprange-java` | Apache Parquet Java 加载、不可变数据、九种搜索策略 |
| `java/iprange-contract` | 可继承的共用契约测试与错误数据验证 |
| `java/iprange-bench` | 候选工厂、独立样本校验与 JMH 测量路径 |
| `rust` | Rust core、JNI、固定工具链与依赖锁文件 |
| `java/iprange-rust` | 可选 Java Native 包装、ABI 检查、Cleaner 及 JNI 边界测试 |
| `tools/evaluation` | 独立 oracle、串行评估、资源保护、报告与证据导出 |

- [Java 搜索算法候选与测试](doc/Java搜索算法候选.md)：策略语义、API、生命周期与复跑命令。
- [Java 候选与性能评估](doc/Java候选与性能评估.md)：首轮评估范围、消费方式与正式测量口径。
- [Rust 搜索策略](doc/Rust搜索策略.md)：九策略语义、索引成本、边界测试和完整配对复跑入口。
- [Rust 接入说明](rust/README.md)：构建、使用、数据与生命周期契约、配对评估及后续范围。
- [IPv4 批量查询可行性验证实施计划](doc/IPv4批量查询可行性验证实施计划.md)：包含更大的后续 Rust/JNI 范围，历史规划；当前实现与实测状态以 Java 与 Rust 配对报告为准。
