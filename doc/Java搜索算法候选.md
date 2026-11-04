# Java 搜索算法候选与测试

日期：2026-11-04。三类新增算法已实现，并以同一批量接口和载荷布局接入评估。默认仍为基础二分；Rust/JNI 仍为预留工程。

## 策略与实现边界

| 标识 | 行为 | 额外常驻索引 |
|---|---|---|
| `binary` | 无符号前驱二分，校验候选终点 | 无 |
| `prefix16` | 16 位高位目录，桶内二分 | 65,537 个 int |
| `prefix20` | 20 位高位目录，桶内二分 | 1,048,577 个 int |
| `prefix16-24` | 先查 16 位目录；起点数至少 4,096 的桶细分为 256 个 24 位子桶 | 一级目录、密集桶的子目录与引用 |
| `interleaved4` / `interleaved8` | 同一线程交错推进 4/8 个独立二分，尾部回到标量查找 | 无 |
| `prefix16-interleaved4` / `prefix16-interleaved8` | 前缀缩小范围后进行 4/8 路交错 | 与 16 位目录相同 |
| `eytzinger` | 层序键数组搜索，候选映射回原始行号并校验终点 | N+1 个键及 N+1 个原始行号 |

所有目录保留桶前的全局前驱候选，覆盖空桶及跨前缀长区间。交错策略用标量局部变量显式展开，不在查询中分配 lane 数组，不引入线程池。Eytzinger 使用 long 计算子节点位置，处理非满树和大索引的乘法溢出问题；本轮没有显式预取或 SIMD。

Eytzinger 保留原排序边界，支持同一数据创建其他策略视图，因此额外索引约 8×(N+1) 字节；真实 N 下约 246.5 MiB。`indexBytes()` 只统计额外基本类型数组，不包含对象头、引用、构建临时内存或 RSS。两级目录的实际大小取决于起点密度。

## 复用依据

继续复用 Apache Parquet Java 解码及 JMH。算法依据作者的 [Array Layouts 论文与实现](https://github.com/patmorin/arraylayout)以及 [交错二分实验](https://lemire.me/blog/2019/09/14/speeding-up-independent-binary-searches-by-interleaving-them/)。这些参考主要是 C/C++ 搜索内核，不能直接成为 Java 11 的 IPv4 区间库。本次实现限于用户指定的策略适配，补齐无符号比较、前驱/终点语义、原行号和批量尾部处理；不引入重复的索引框架，也不继承参考实现的加速比结论。

## API 与生命周期

`JavaIpRangeSnapshot.open(Path)` 与原 `open(Path, boolean)` 保持兼容，boolean=true 仍表示 prefix16。新入口为：

```java
try (JavaIpRangeSnapshot snapshot =
         JavaIpRangeSnapshot.open(path, SearchAlgorithm.PREFIX_20)) {
    snapshot.lookupBatch(ips, inputOffset, length, rows, outputOffset);
}

try (JavaIpRangeSnapshot base = JavaIpRangeSnapshot.open(path);
     JavaIpRangeSnapshot view = base.withAlgorithm(SearchAlgorithm.EYTZINGER)) {
    // view 共享 base 的不可变边界与载荷，只额外构建自己的搜索索引。
    // 关闭 base 不影响已创建的 view，关闭 view 也不影响其他视图。
    view.lookupBatch(ips, 0, ips.length, rows, 0);
}
```

`SearchAlgorithm.fromId` 解析上表标识；`algorithm()` 返回当前策略。所有视图的 datasetVersion、行号和八字段语义相同。视图关闭后新调用失败，已返回的 Java 结果仍有效。

## 核验与性能入口

```bash
./tools/evaluation/build.sh verify
(cd tools/evaluation && python3 -m unittest -v test_runner)

# 使用既有独立 oracle；如数据更换，先重新执行 oracle.py。
python3 tools/evaluation/run.py verify --verify-algorithms \
  --oracle benchmark-results/oracle/oracle.tsv --output benchmark-results/algorithms-verify

# 全部 9 个策略 × 3 批次 × 3 并发 × 3 结果路径 × 2 模式 = 486 项。
python3 tools/evaluation/run.py benchmark --algorithms-quick \
  --output benchmark-results/algorithms-quick

# 只快速评估一个策略，预期 54 项。
python3 tools/evaluation/run.py benchmark --algorithms-quick --algorithm eytzinger \
  --output benchmark-results/eytzinger-quick

# 对指定场景做正式独立 fork 复测，保留原正式预热和测量默认值。
python3 tools/evaluation/run.py benchmark --algorithm prefix20 --only real-b4096-c1 \
  --output benchmark-results/prefix20-formal
```

JMH 的 `algorithm=legacy` 是兼容模式，按旧 prefix 参数选择 binary/prefix16；显式算法标识优先于该旧参数。新算法快速矩阵包含行号、八字段 UTF-8 和字符串，B=1,024/4,096/65,536，C=1/4/8；输入池 Q=1,000,000，线程分摊输入，固定种子 20261104。字符串解析继续复用同一接口，不作为算法矩阵中的额外第四路径。

快速矩阵只加载一次全量数据，按固定种子打乱算法顺序，逐策略构建/关闭索引视图；JMH forks=0，1×1 秒预热、1×1 秒测量。构建、数据加载、输入生成均在查询计时之外，单独记录索引构建时间及数组字节。各策略共享 JIT/GC 历史，JMH non-forked 模式仅作探索性分析，不能与正式长测混比或据此宣称稳定最优配置。资源保护仍为系统可用内存 2 GiB 下限及进程组 RSS 12 GiB 上限，连续两次越界则停止。

原有 128 项快速矩阵及历史报告保持独立。新矩阵逐项核验算法、B/C、路径、模式、数据类别和命中率，不接受缺项、额外项或重复项。原始记录保存在独立 benchmark-results 目录，最终结果报告另行记录，避免改写历史测量。覆盖完整后可以复跑导出，归一化机器路径并保留原始指标、直方图和资源采样：

```bash
python3 tools/evaluation/export.py benchmark-results/algorithms-quick \
  doc/verification/java-algorithms-quick
```
