# Rust JNI IP 区间批量查询技术设计

版本：1.1（设计交付稿；明确同等设计的 Java 库基线）  
日期：2026-11-04  
状态：需求背景已核对；技术方案待评审与实现；性能尚未测试。  
适用对象：Java 11 主程序、Rust Native Library、Parquet 静态 IP 区间数据。  
来源：原对话《评估Rust Parquet库》及本次明确要求。本文以本次要求为准，不沿用原讨论中的性能预估作为结论。

## 1. 背景与设计结论

业务需要对大量 IP 地址执行区间归属查询：给定 `val`，找到满足 `bd <= val <= ud` 的记录，返回其归属信息。原始数据采用 Parquet 保存，区间按起点严格升序且不重叠。该约束使问题可以简化为“有序起点数组的前驱查找，再校验终点”，无需在在线查询路径执行通用 SQL 或重新扫描 Parquet。

本文建议将 Parquet 解码、数据校验、不可变索引和批量查询封装为 Rust Native Library，通过 JNI 与 Java 11 主程序集成。Java 保留业务编排、请求调度与结果消费职责；Rust 在进程内维护索引与载荷快照。查询没有网络往返，但引入了跨语言调用、数组搬运、原生内存管理和跨平台制品维护成本。

技术路线为：先交付连续数组加标准二分查找，再根据同机公平 Benchmark 决定前缀目录、排序后双指针和内部并行是否启用。Rust core 更快不等于 JNI 端到端更快；只有包含数据搬运和结果消费的实测才能支持采用结论。正式基线为采用相同设计思想实现的纯 Java 11 查询库：同样从 Parquet 加载，使用连续基本类型数组、有序区间查找、批量 API、不可变快照和版本切换。基本类型数组是该 Java 库的内部实现方式，单独的二分函数微基准仅用于分析计算成本，不能替代库级对比。

## 2. 已知约束、建议方案与待验证假设

| 分类 | 内容 | 对设计的影响 |
| --- | --- | --- |
| 已知约束 | Parquet 中 IP 区间按起点严格排序、互不重叠 | 可用前驱查找，单次最多命中一行 |
| 已知约束 | 查询语义为闭区间 `[bd, ud]` | 起点、终点均应命中；相邻区间不能共享同一端点 |
| 已知约束 | Java 11 为接入目标，Rust JNI 为主方案 | 不依赖更高版本 Java 的外部函数接口 |
| 已知要求 | 高性能批量查询、可移植 Native Library | 批次 API、目标平台制品和集成测试为交付内容 |
| 已知要求 | Benchmark 主规模 `N=10,000,000`、`Q=1,000,000` | 这是评估规模，不等于已确认生产数据量 |
| 建议方案 | 启动或刷新时解码，常驻不可变内存快照 | 查询不做文件读取或解压；需完整内存预算 |
| 建议方案 | IPv4 首批实现；IPv6 单独类型、索引及接口 | 是否首版交付 IPv6 仍需确认 |
| 建议方案 | 全量校验，发现错误拒绝发布快照 | 不静默重排、去重或裁剪区间 |
| 待验证假设 | 数据与并发刷新峰值能装入目标 JVM 所在主机 | 实测载荷大小、RSS、并发和旧版本保留时间 |
| 待验证假设 | JNI 批量方案在目标业务上收益足够 | 与采用相同设计思想的 Java 库比较端到端吞吐、P99、CPU、内存 |
| 待确认事项 | 真实 schema、载荷字段、平台矩阵、SLO、刷新频率 | 未确认前不得宣称生产可交付或性能验收通过 |

## 3. 业务与数据契约

### 3.1 区间与数据集

建议将输入规范冻结为以下不变量：`starts.len == ends.len == N`；每行 `bd <= ud`；相邻行 `bd[i] < bd[i+1]` 且 `ud[i] < bd[i+1]`。允许空洞、单点区间和空数据集。空数据集的所有查询均为未命中；是否禁止发布空数据集可由业务发布策略控制。

“有序”必须覆盖整个快照，而不仅是单个 RecordBatch、Row Group 或文件。多文件数据集必须由 manifest 明确文件顺序；加载器连续验证跨批次、跨文件边界。不能按目录枚举顺序拼接，也不能只相信 Parquet statistics 或排序元数据。

建议 `bd`、`ud` 非空，拒绝 null、负数越界、反向区间、重复起点、重叠和非预期类型。坏数据返回具体文件、Row Group 和行号；生产日志避免直接输出整条业务载荷。字符串地址仅允许在显式配置的离线/加载转换阶段解析，热路径只接受二进制或整数。

### 3.2 IP 编码

IPv4 使用无符号 32 位整数，数值由网络字节序确定，如 `1.2.3.4 = 0x01020304`。Rust 使用 `u32`；Java `int` 承载同一 32 位 bit pattern，负的 Java `int` 也是合法 IPv4，不能据此拒绝。Java `long` 便利入口若提供，必须限定在 `[0, 4294967295]`，显式检查后再转换。

Parquet 建议采用 `INT32 + UINT_32` 逻辑类型。若现有数据是 INT64 或文本，应使用显式 schema 适配器，逐值范围验证后转换；不得按符号隐式解释。本文尚未收到真实 Parquet 样本，该映射属于建议契约。

IPv6 建议使用网络序 16 字节输入，Rust 内部 `u128`；C ABI 使用字节缓冲区，禁止直接导出 Rust `u128` 布局。IPv4 与 IPv6 分离；IPv4-mapped IPv6 是否归一化为 IPv4 必须由业务明确，首版默认不自动转换。

### 3.3 行号与业务载荷

行号是快照内从 0 开始的逻辑位置，不是跨版本业务 ID。保留源数据顺序时，行号对应 manifest 文件顺序中的累计行偏移。若业务存在稳定 ID，应作为独立载荷字段保留。

建议 ABI 对外使用有符号 64 位行号，`-1` 表示未命中，其他负值保留。快照总行数限制为 `N <= i64::MAX`，加载时还需满足地址空间与内存限制。Java 使用 `long[]`，避免后续超过 `int` 行号范围时破坏 ABI。

返回结果必须绑定 `datasetVersion`。单独记录版本文本并不能保住旧数据：若需要根据行号读取载荷，调用者还必须持有对应快照租约；禁止把旧行号交给当前活动快照解释。

## 4. 功能需求与非目标

### 4.1 功能需求

1. 从指定不可变 manifest 加载 Parquet，投影必需列，校验 schema、校验和及全局区间不变量。
2. 按输入顺序输出等长结果，保留重复查询；支持空批次、未命中和显式容量限制。
3. 支持多线程共享只读快照，查询、关闭、刷新并发时不发生悬空引用或版本混用。
4. 提供生命周期、版本信息、错误码、批量行号查询和批量载荷提取接口。
5. Java 11 可加载对应平台动态库，并在 ABI 不兼容时启动失败。
6. 刷新失败继续使用旧版本；成功时只发布完整且校验通过的新快照。
7. 输出可重复 Benchmark、集成测试及平台构建证据。

### 4.2 非目标

不支持任意 SQL、重叠区间的多结果搜索、最长前缀路由匹配、在线增删单行、跨 JVM 共享堆内索引、分布式事务或所有节点同时切换版本。不将 Redis、Sidecar 或网络查询引入主架构。不承诺 Rust 固定快于 Java，也不承诺任意平台使用同一二进制。

首版不提供 Java 直接持有 Rust 内存地址的零拷贝载荷视图，不默认启用内部线程池，不建设通用数值区间框架。IPv6、DirectByteBuffer 加速路径及复杂索引属于有条件后续范围。

## 5. 总体架构

```text
Java 11 业务层
  └─ IpRangeEngine / Snapshot（AutoCloseable）
       └─ JNI 批量适配层（参数检查、数组复制、异常映射）
            └─ 共享 runtime / handle service
                 ├─ 不可变 Snapshot → starts / ends / payload
                 ├─ 查询 core → 二分 / 可选优化
                 └─ 加载器 → Parquet + Arrow → 校验 → 候选快照
                        ↑
                 本地版本 manifest 与 Parquet 文件

独立 C ABI 导出 → 同一 runtime / handle service
刷新控制 → 构建候选版本 → 原子替换活动 Arc → 旧租约自然回收
```

C ABI 与 JNI 是两个外部边界，复用同一 Rust 服务层和句柄规则。建议首版将 JNI 与 C 导出打包在一个 `cdylib` 中，避免双动态库加载与注册表重复。JNI 直接调用内部 Rust 服务函数，不必再绕一次 C 导出；两种边界必须使用一致的契约测试。若未来要求 JNI 只依赖稳定 C ABI，再拆成 shim 与 core 两个制品，并单独验证动态链接部署。

技术选型优先复用 Apache Arrow Rust 的 Parquet 解码能力、标准库 `partition_point`、`Arc`、成熟 JNI 绑定与可选 `arc-swap`。自研范围限于业务校验、批量契约、快照生命周期和适配层。DataFusion/Polars 可用于通用分析，但本场景不需要它们的查询规划能力；引入会增加依赖和维护面。B-Tree、区间树和路由 Trie 也不是当前不变量所必需。

## 6. Parquet 加载与内存布局

### 6.1 加载流程

1. 读取 manifest，验证 schemaVersion、datasetVersion、文件路径、大小、checksum、预期行数和文件顺序。约束来源目录，避免读取未发布或正在被覆写的文件。
2. 使用 `ParquetRecordBatchReaderBuilder` 分批读取，投影 `bd`、`ud` 与配置的业务字段。该 reader 将 Parquet 读取为 Arrow RecordBatch；这不意味着 Parquet 压缩数据能直接零拷贝查询。[Parquet Arrow Reader](https://docs.rs/parquet/latest/parquet/arrow/arrow_reader/type.ParquetRecordBatchReaderBuilder.html)
3. 将边界列转换并追加到连续数组，跨批次保存上一行终点；遇到违例立即失败。不得通过静默排序掩盖上游数据错误。
4. 保留载荷列的 Arrow batches 及累计起始偏移。处理空 batch，保证偏移表严格有效。
5. 校验实际总行数和 manifest 一致，计算内存用量并按需构建前缀目录，执行边界抽检，封装不可变快照。
6. 仅在全部步骤成功后注册或发布快照。失败候选由 RAII 释放，当前活动版本不变。

限制压缩文件大小之外，还需限制元数据大小、解码后列长度、单值长度、总行数、载荷总字节及加载峰值。Parquet 压缩比不能用作常驻内存的上界。读取过程的所有长度和乘加运算使用 checked arithmetic。

### 6.2 推荐快照结构

```rust
// 设计示意，不是已实现的源码。
struct SnapshotV4 {
    version: DatasetVersion,
    starts: Box<[u32]>,
    ends: Box<[u32]>,
    payload: PayloadStore,
    // 可选：前缀目录；不属于首版必需项。
}
// Arc<SnapshotV4> 在外层管理共享生命周期。
```

采用 SoA：二分过程访问 `starts`，命中候选后读取一次 `ends`。相对 AoS 的收益必须实测。建议首版明确复制边界列为连续数组，换取简单的寻址与生命周期；复制完并释放临时批次后，不再长期重复保留 `bd/ud` 列。载荷仅保留投影字段，但需确认 Arrow 切片或共享 buffer 是否仍保留较大的底层分配。

若未来复用 Arrow 边界 buffer，必须处理多 batch 非连续、null bitmap、offset、类型及对齐，不能直接假定一个 Parquet 文件对应一个连续 `u32[]`。Arrow 的列式格式支持不同类型的 buffer 和 validity 表达，实际所有权由实现维护。[Arrow Columnar Format](https://arrow.apache.org/docs/format/Columnar.html)

全局行号 `r` 的载荷定位：对 batch 起点偏移做 upper_bound，确定 batch，再以 `r - batchStart` 访问局部行。此步骤通常为 `O(log K)`，K 为 batch 数；批量载荷提取可按 batch 聚合，但必须恢复输出顺序。不要隐瞒载荷取值与字符串转换开销。

### 6.3 容量预算

| 项目 | N=10M 的原始容量 | 说明 |
| --- | --- | --- |
| IPv4 起点和终点 | 80,000,000 B，约 76.3 MiB | 仅 `8N`，不是进程 RSS |
| IPv6 起点和终点 | 320,000,000 B，约 305.2 MiB | 仅 `32N`，独立索引 |
| Q=1M IPv4 输入 | 4,000,000 B | 每份输入缓冲区 |
| Q=1M 行号输出 | 8,000,000 B | 每份输出缓冲区 |
| 载荷、偏移表、目录 | 按实际 schema 计算 | 字符串与 null 信息不可省略 |

基线 JNI 若同时持有 Java 输入/输出和 Native 输入/输出临时区，仅这一组 Q=1M 缓冲区约占 24 MB；并发、排序临时数组和载荷另计。工作缓冲按实际批次 B 配置，不要求每次都分配 Q 大小。

刷新峰值预算为：旧活动快照 + 新快照 + 解码/转换临时区 + 在途查询缓冲 + 尚被租约持有的更旧快照 + Java 堆及其他 Native 开销。不能简单认为峰值永远等于两倍索引。首版串行刷新，并对保留版本数、租约数量和内存余量设置准入限制；不能强行释放仍在使用的旧版本。

## 7. 区间查找算法与正确性

```rust
fn find(starts: &[u32], ends: &[u32], val: u32) -> Option<usize> {
    // 构造器已保证等长、有序且区间不重叠。
    let p = starts.partition_point(|&start| start <= val);
    let i = p.checked_sub(1)?;
    (val <= ends[i]).then_some(i)
}
```

`partition_point` 返回满足谓词的前缀结束位置，因此这里得到第一个 `start > val` 的下标，其前一项即候选区间。前提是谓词在数组中形成 true 前缀与 false 后缀。[Rust slice::partition_point](https://doc.rust-lang.org/std/primitive.slice.html#method.partition_point)

正确性依据：所有晚于候选的区间起点都大于 val；所有早于候选的区间终点都小于候选起点，因此不可能包含 val。候选终点若小于 val，则 val 位于空洞，结果为未命中。时间复杂度 `O(log N)`，单次额外空间 `O(1)`；N=10M 的比较次数量级约为 24，不是延迟预测。

必须覆盖：空数据、单区间、首起点前、首起点、每个终点、空洞、尾终点后、`0`、`u32::MAX`、重复查询。不得用 `ud + 1` 判断相邻关系，以免最大地址溢出；直接比较 `ud[i] < bd[i+1]`。

## 8. 批量 API 与结果契约

### 8.1 Java 公共 API 草案

```java
public final class IpRangeEngine implements AutoCloseable {
    public static IpRangeEngine open(Path manifest) throws IOException;
    public Snapshot acquireSnapshot();
    public RefreshResult refresh(Path manifest) throws IOException;
    public EngineInfo info();
    public void close();
}

public final class Snapshot implements AutoCloseable {
    public String datasetVersion();
    public long rowCount();
    public void lookupBatch(int[] ips, int inputOffset, int length,
                            long[] rowIds, int outputOffset);
    public PayloadBatch fetchBatch(long[] rowIds, int offset, int length,
                                    int[] fieldIds);
    public void close();
}
```

这些是待实现的契约草案，不是可直接编译的完整类。Java 11 使用普通类表达 `EngineInfo`、`RefreshResult` 和 `PayloadBatch`，不使用 record。`lookupBatch` 不逐项创建对象；输出长度等于 length，顺序与输入一致，未命中为 `-1`。

null 数组、负 offset/length、越界或超过 maxBatch 返回异常；使用 `length <= array.length - offset` 等防溢出检查。零长度合法，但仍校验句柄和参数。数组在调用期间不得被其他线程修改，同一输出区间不得被并发写入。输入与输出由 Java 拥有，Native 不保留指针。

调用成功才可消费输出；任何异常后本次输出区间均视为无效，调用者必须整体丢弃或重试，不能猜测哪些位置已经写入。不提供部分成功计数。查无记录是正常业务结果，不是异常。

### 8.2 载荷提取

`fetchBatch` 只在同一个 Snapshot 上解释行号。`-1` 输出未命中槽位，越界行号整体报错。区分“未命中”和“命中但字段为 null”：建议 PayloadBatch 包含 matched bitmap、每列 validity bitmap 和相应数值数组或 UTF-8 data/offsets。字符串列采用打包 UTF-8，避免热路径逐行创建 Java String。

首版建议支持明确列出的整数、布尔和 UTF-8 类型；嵌套类型拒绝并返回 UNSUPPORTED_SCHEMA，除非新增适配规范。具体 fieldId 与 schemaVersion 的映射须结合真实数据冻结，禁止凭空指定 location/ISP 等字段为现有事实。PayloadBatch 在 Java 端拥有拷贝后数据，Snapshot 关闭后仍可使用。

为降低重复边界调用，可在后续增加 `lookupAndFetchBatch`，在一次快照租约内完成查找和提取。Benchmark 必须分别报告“仅行号”和“带实际业务字段”，不能把前者当作完整业务性能。

## 9. Rust crate 分层与依赖

| 模块 | 职责 | 约束 |
| --- | --- | --- |
| `iprange-core` | 不变量、查找、批量算法、索引布局 | 不依赖 JNI、文件系统或 Arrow；默认安全 Rust |
| `iprange-parquet` | Parquet 解码、schema 适配、载荷、验证报告 | 复用 Apache Arrow Rust；不处理 Java 对象 |
| `iprange-runtime` | engine、快照租约、句柄表、刷新、资源预算 | 共用生命周期和错误模型 |
| `iprange-native` | `cdylib`，C ABI 与 JNI adapters | unsafe 集中，边界隔离及错误翻译 |
| `iprange-bench` | 数据生成、Rust 基准、正确性参考 | 不进入生产热路径 |
| `java/iprange` | Java 11 API、Native loader、JMH/集成测试 | 不复制区间业务规则到 JNI wrapper |
| `java/iprange-java` | 同等设计的纯 Java 基线库，含加载、索引、批量查询与快照 | 独立实现相同业务契约；不依赖 Rust/JNI |

依赖候选：`parquet` 与对应 Arrow crates 使用兼容版本；`jni` 用于 Java 交互；`arc-swap` 可用于读多写少的活动快照；`criterion` 用于 Rust 基准；C 头文件可用生成工具减少漂移。具体版本需在实现开始时锁定到 Cargo.lock、rust-toolchain 和 Java 构建文件，不能将文档中的 latest URL 当作版本约束。JNI crate 的不同版本 API 有变化，示意代码不绑定某个历史版本。[jni crate](https://docs.rs/jni/latest/jni/)

## 10. C ABI 与 JNI 边界

### 10.1 C ABI 草案

```c
#include <stdint.h>
#include <stddef.h>
typedef uint64_t ipr_handle_t; /* 0 保留为无效句柄 */
typedef int32_t ipr_status_t;

uint32_t ipr_abi_version(void);
ipr_status_t ipr_engine_open_v1(const uint8_t *manifest_utf8, size_t path_len,
                              ipr_handle_t *out_engine);
ipr_status_t ipr_snapshot_acquire_v1(ipr_handle_t engine,
                                   ipr_handle_t *out_snapshot);
ipr_status_t ipr_lookup_v4_v1(ipr_handle_t snapshot,
                            const uint32_t *ips, size_t count,
                            int64_t *row_ids, size_t capacity);
ipr_status_t ipr_engine_refresh_v1(ipr_handle_t engine,
                                 const uint8_t *manifest_utf8, size_t path_len);
ipr_status_t ipr_handle_release_v1(ipr_handle_t handle);
```

此外必须实现 version/info、逐调用错误信息及 payload sizing/copy 接口；上面只展示核心签名。错误信息采用调用者提供的 buffer 或显式 error handle，不能依赖全局 last_error；若使用线程局部 last_error，必须约定同线程立即读取且不可跨 Java 调度，该方案不作为首选。

ABI 规则：仅使用固定宽度整数、显式长度、指针及 `#[repr(C)]` 结构；结构含 size/version 便于扩展；不导出 `Vec`、`String`、引用、Rust enum、trait object 或 `usize` 作为跨平台持久化字段。`size_t` 只表示同进程缓冲区长度。ABI 主版本不兼容则拒绝加载；新增可选能力通过 feature bits 协商。

输入指针仅在调用内借用；输出由调用者分配和释放，Native 不把 Rust 分配的数组交给 Java free。若将来提供 Native-owned buffer，必须同库提供 release，且加入严格租约规则。指针需有效、满足对齐、不发生可变别名；count 为 0 可接收 null，但 Rust 必须分支处理，不能直接对 null 构造 slice。count/capacity 和字节数转换全部检查。

C ABI 无法证明任意非空指针真实可读写，调用者必须遵守契约；JNI 层通过 Java 数组及受控复制构建合法内存。裸地址不作为 Java handle。UTF-8 路径需显式转换为平台路径，包含 NUL 拒绝；Windows Unicode 路径、空格及长路径必须在平台测试中覆盖。

### 10.2 JNI 实现策略

建议使用 `extern "system"` JNI 入口，在 JNI_OnLoad 注册固定 native methods 并做 ABI/feature 检查。内部 C 导出使用 `extern "C"`。两者调用约定不能混用。动态库使用 `cdylib`，而非依赖 Rust 自有动态 ABI 的 `dylib`。[Rust Linkage](https://doc.rust-lang.org/reference/linkage.html)

首版采用 GetIntArrayRegion 等区域读取将输入复制到 Native 暂存区，查询后以 SetLongArrayRegion 回写结果。成本清晰、无需长期固定 Java 堆；按批次复用且限制 Native 暂存容量。`Get*ArrayElements` 可能复制，不能宣称零拷贝。避免在 GetPrimitiveArrayCritical 临界区执行大批查询、分配、阻塞或并行等待。[Java 11 JNI Functions](https://docs.oracle.com/en/java/javase/11/docs/specs/jni/functions.html)

后续若测试 DirectByteBuffer，需明确容量、只读性、对齐、offset 和字节序；Java 视图显式设置约定字节序。DirectBuffer 只在同步调用内借用，Java 对象须保持可达；不能让 Native 后台线程在调用返回后继续访问。零拷贝是否成立要分输入来源、预处理和结果消费全流程判断。

JNIEnv 仅在所属线程使用，不能缓存后跨线程传递；Native 工作线程若仅做查找，禁止触碰 JNI 或 Java 对象。必要的跨调用引用使用正确的 GlobalRef 并释放；批量操作限制 local references 数量。发现 pending Java exception 后按 JNI 规范收尾并返回，不继续执行普通 JNI 调用，不覆盖原始异常。

## 11. Java 11 接入示例

```java
// API 使用示意；实现类与载荷 schema 在开发阶段提供。
try (IpRangeEngine engine = IpRangeEngine.open(manifestPath);
     Snapshot snapshot = engine.acquireSnapshot()) {
    int[] ips = new int[] { 0x01020304, 0xFFFFFFFF };
    long[] rowIds = new long[ips.length];
    snapshot.lookupBatch(ips, 0, ips.length, rowIds, 0);
    PayloadBatch payload = snapshot.fetchBatch(
        rowIds, 0, rowIds.length, configuredFieldIds);
    consume(snapshot.datasetVersion(), rowIds, payload);
}
```

按请求或离线任务持有 Snapshot：单批默认同一版本；若 Q=1M 拆成多个 B 大小批次且业务要求整体一致，必须在全部批次外持有同一个 Snapshot，而不是每批重新获取。长任务会延长旧版本寿命，应计入刷新内存预算。

所有 Java wrapper 实现 AutoCloseable，close 幂等；显式 try-with-resources 是主路径。Cleaner 仅作为泄漏兜底，不能承诺及时释放；清理动作不能强引用 owner，不能执行长时间阻塞工作。[Java 11 Cleaner](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/lang/ref/Cleaner.html)

加载器解包经过校验的本平台 Native Library 后，以绝对路径调用 `System.load`；禁止从不受控工作目录搜索同名库。Java 编译使用 `--release 11`，在真实 JDK 11 上执行集成测试。[Java 11 System.load](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/lang/System.html#load(java.lang.String))

## 12. 所有权、生命周期与并发安全

### 12.1 句柄模型

建议 runtime 使用类型化注册表：`handle → Arc<Engine>` 或 `Arc<Snapshot>`。使用正值范围内的单调 64 位 token，永不复用，耗尽时拒绝分配，避免关闭后旧句柄误指向新对象。不要将 Box 原始地址转换为 jlong 后直接解引用。

每次调用在注册表短读锁下查找并 clone Arc，立即释放锁，再执行查询。release 在短写锁下移除条目，解锁后执行可能耗时的析构。快照内查找不加锁，但注册表、引用计数和生命周期管理有成本，因此不宣称整个 JNI 调用路径完全无锁。

线性化语义：若查询先取得 Arc，随后 close 不影响该在途查询；若 close 先移除 token，新查询返回 CLOSED_HANDLE。Java wrapper 原子地将自身 token 置 0，重复 close 不再调用 release；Native 对未知 token 返回明确错误。输入数组和输出数组仍遵守调用者不并发修改的约束。

### 12.2 Engine、Snapshot 和刷新

Engine 拥有活动快照指针与刷新状态；Snapshot 租约独立持有 Arc。Engine 关闭后拒绝新 acquire/refresh，已获得 Snapshot 继续有效，直至其关闭。每个查询再取得临时 Arc，因此 Snapshot.close 与查询竞争也不会释放在途内存。

刷新构建不持有查询锁。发布时持有短控制锁，检查 engine 未关闭、预期基线版本仍一致，再替换活动快照。close 与发布通过同一控制锁定义顺序：已关闭则候选不能发布。首版每 Engine 仅允许一个刷新，第二个返回 BUSY，不隐式启动多个大内存加载任务。

活动快照可采用 `ArcSwap`；其提供可原子替换的 Arc 容器，适合读多写少的快照场景。每次 acquire 取得一个完整快照，索引、载荷和版本在同一对象内发布。[arc-swap](https://docs.rs/arc-swap/latest/arc_swap/)

### 12.3 资源与可观测性

统计 activeVersion、activeRows、snapshotBytes、retainedVersions、leaseCount、loadDuration、refreshFailures、查询批次与错误码。Native RSS 不属于 Java heap，不能只看 `-Xmx` 判断可用内存。限流通过批次数和工作缓冲预算控制，避免并发调用各自分配巨量临时数组。

## 13. 错误处理与 panic 隔离

| 错误类别 | C 状态示例 | Java 映射建议 | 是否可保留旧版本 |
| --- | --- | --- | --- |
| 非法参数/长度 | INVALID_ARGUMENT | IllegalArgumentException | 是 |
| 已关闭/错误类型句柄 | CLOSED_HANDLE / BAD_HANDLE | IllegalStateException | 不影响其他实例 |
| 文件读取失败 | IO_ERROR | IOException | 是 |
| schema 或区间违例 | INVALID_DATA | 自定义 DataValidationException | 是 |
| ABI 不兼容 | ABI_MISMATCH | LinkageError | 启动失败，不静默降级 |
| 容量不足/刷新冲突 | RESOURCE_LIMIT / BUSY | 自定义异常或明确刷新结果 | 是 |
| 内部异常 | INTERNAL_PANIC | NativeQueryException | 停止相关失败操作并告警 |

正常失败使用 Rust Result，不用 panic 表达文件损坏或未命中。所有外部入口，包括 JNI 初始化、C 导出和清理路径，都需保证 unwind 不跨边界；在可恢复入口内使用 catch_unwind，并以 `panic=unwind` 构建需要捕获的 Rust 代码。对可变共享状态的恢复边界进行审查，不能用 AssertUnwindSafe 粗暴掩盖不变量破坏。

catch_unwind 只捕获 unwinding panic，不能捕获 abort、段错误、未定义行为或所有 OOM；panic hook 在捕获前可能运行。因此该机制不等于进程隔离，也不能保证 Native 错误不会导致 JVM 退出。[Rust catch_unwind](https://doc.rust-lang.org/std/panic/fn.catch_unwind.html)

使用 try_reserve 和资源上限降低分配失败风险，但不承诺所有依赖的分配都可恢复。错误格式化和异常转换路径也需避免二次 panic。共享可变状态若已失去一致性，应将 Engine 标为 failed，拒绝新操作；不可变快照仅在确认未受影响时继续服务。

## 14. 数据刷新与版本切换

建议 manifest 包含：schemaVersion、datasetVersion、创建时间（UTC）、有序文件列表、每文件大小/行数/SHA-256、IP family、字段映射、数据总行数。datasetVersion 为不可变标识，同一版本出现不同 checksum 应拒绝，不按版本字符串字典序判断新旧。

发布流程：生产者先写版本目录的临时文件，完成内容与校验后发布 manifest；消费者只加载已完成的不可变版本。活动 manifest 指针的替换需遵循目标文件系统原子发布能力；不能假定任意跨文件系统 rename 都是原子的。

消费者刷新状态：`IDLE → LOADING → VALIDATING → READY_TO_PUBLISH → ACTIVE`，任意失败回到旧 ACTIVE 并记录失败原因。启动无旧版本且加载失败则 readiness 失败；运行期间新版本坏数据不影响旧版本。相同版本及 checksum 的刷新返回 NO_CHANGE。

版本切换在单 Engine 内原子完成。已获取的 Snapshot 保持旧版本，新 acquire 得到新版本；单批及同一租约中的多批查询一致。多 JVM 允许暂时版本不同，可由业务编排检查版本，但分布式同步切换不属于本库。

回滚使用同一加载、校验和原子发布机制重新激活明确指定的旧版本；不能回退到不匹配 schema/ABI 的数据。保留版本文件、最大租约时长与内存水位由部署策略控制。超时租约可告警并拒绝后续刷新，不能通过超时强制释放在途内存。

## 15. 跨平台构建与 JAR 分发

建议候选平台：Linux x86_64/aarch64（GNU libc）、Windows x86_64（MSVC）、macOS x86_64/aarch64。是否全部作为首版支持范围待确认。musl、32 位 JVM、其他 CPU 架构默认不宣称支持。每个平台必须匹配 OS、CPU、ABI/libc 与最低运行系统版本。

建议发布一个 Java API JAR 与按 classifier 拆分的 Native JAR，例如 `linux-x86_64-gnu`、`windows-x86_64-msvc`、`macos-aarch64`。业务显式选择部署平台依赖；开发便利的全平台 bundle 可后续提供，避免默认携带所有二进制。

Native 资源建议路径为 `/META-INF/native/<platform>/<library>`，附带 nativeVersion、abiVersion、features、构建 revision 与 checksum。加载器规范化架构名称，拒绝未知平台；libc 不能只依赖 `os.name` 判断，可采用显式部署配置和加载验证。

解包到版本化私有目录，用临时文件加原子替换及跨进程互斥避免竞争；检查完整性和目录权限，失败给出平台/依赖诊断。checksum 检测损坏，发布来源信任还依赖制品仓库和签名。Windows 已加载 DLL 不能随意删除；退出清理失败可保留缓存并在下次启动清理。受限/noexec 目录提供管理员配置的预安装绝对路径。

Native Library 由共享的加载器统一加载；多 classloader、应用服务器热重载和同进程多版本不能假定可行，需作为明确的集成限制或专门测试项。不尝试运行中强制卸载动态库，数据刷新不依赖库卸载。

构建固定 Rust toolchain、Cargo.lock、JDK 11、编译参数及依赖校验；基线不用 `target-cpu=native` 分发到未知机器。跨编译产物仍须在真实目标平台 smoke test，验证动态依赖、最低 glibc/macOS、VC Runtime、压缩 codec 依赖和 Unicode 路径。输出许可证清单、SBOM、符号文件与可追溯构建信息；性能专用 CPU 优化制品需独立命名和运行时能力检查。

## 16. 性能优化路线与启用条件

### 16.1 阶段 A：标准二分

先用连续 starts/ends、标准 partition_point、批量 JNI 和 caller-owned 输出建立基线。索引一次加载、查询循环无逐行分配。Java 采用相同数据宽度与算法；先观察复制、缓存缺失、载荷取值哪个占主导，再优化。

### 16.2 阶段 B：前缀目录

IPv4 可按高 k 位建立目录，先评估 `k=8/12/16`。对桶 p 的数值起点 L 和下一桶起点 U，定义 `a=lower_bound(starts,L)`、`b=lower_bound(starts,U)`，在 `[a.saturating_sub(1), b)` 中做同样的 upper_bound 和终点校验；最大桶的 U 用 u64 表达 `2^32`，不能在 u32 中溢出。

额外纳入 a 前一项用于覆盖跨前缀区间，即使某个区间跨度覆盖很多桶也不会漏查。空桶可能只有这个前驱候选，仍需检查终点。索引构建可线性扫描边界，查询局部复杂度为 `O(log m)`，m 是候选数量；偏斜数据可能使 m 很大，不能保证每桶均匀。

若保存 65,537 个 u64 起点偏移，k=16 的目录约 524 KB；若存 lo/hi 双数组容量约翻倍。目录增加缓存工作集，是否有收益必须在真实分布下验证。Java 基线库同时实现相同优化，避免把算法收益归因于语言。

### 16.3 阶段 C：批量排序与双指针

保存 `(ip, originalPosition)`，按无符号 IP 排序；在首个查询附近定位起始区间，顺序扫描查询与区间，记录命中后按 originalPosition 恢复输出。重复 IP 保持相同结果。输入已排序时可跳过排序，但必须由接口契约声明或验证，不能凭猜测。

复杂度为 `O(Q log Q + log N + S + Q)`，S 为覆盖范围内实际扫描的区间数；最坏接近 N。额外空间为查询排序及恢复缓冲。对于稀疏查询或小批次，扫描大量无关区间可能更慢；阈值取决于 B、覆盖范围、分布与内存带宽，必须通过实测选择。排序、恢复和分配时间必须计入端到端结果。

### 16.4 阶段 D：按需并行

默认 Java 调用线程执行，Rust 内部单线程；多 Java 线程可并发读取同一快照。仅在大批离线任务实测获益时启用有界工作池，例如复用成熟并行库，而非每次调用创建线程。

设置全局线程预算，避免“Java 并发数 × Rust 内部线程数”过度订阅。内部任务只访问 Native 数据和互不重叠输出，完成后由原 JNI 线程回写；记录 NUMA、内存带宽和 P99 变化。SIMD、Eytzinger 等布局仅在前述优化不足时另立实验，不属于首版承诺。

## 17. Benchmark 设计

### 17.1 同等设计的 Java 库与比较层次

| 对象 | 测量范围 | 防止失真 |
| --- | --- | --- |
| 同等设计的 Java 库（正式基线） | 从 Java 公共 API 调用到结果可消费，含参数校验、快照获取、批量查找、结果写入及所选载荷提取 | 使用连续 `int[] starts/ends`、相同 upper_bound 和 `long[]` 输出；具备加载、刷新及生命周期能力 |
| Rust core | 相同 `u32[]`、算法、查询序列和输出 | Release 构建，消费结果，包含所选算法必要临时工作 |
| Rust JNI 批量 | Java 调用到结果可消费，含句柄、复制、查找和回写 | 不用纯 core 时间替代端到端结果 |

正式采用判断比较“Java 业务调用方 → 纯 Java 库”和“Java 业务调用方 → JNI → Rust 库”两条完整路径。Rust core 是辅助计算基准，不是替代 Java 库的唯一比较对象。Java 基线库应独立实现相同契约，不通过 JNI 复用 Rust 查询，也不只包装一个孤立的二分函数。

两库的设计与测量对齐要求如下：

| 方面 | 对齐要求 |
| --- | --- |
| 输入与校验 | 相同 Parquet 数据、schema、全局排序/不重叠校验和投影字段；分别使用本语言成熟读取库，披露解码差异 |
| 内存与查找 | 相同宽度的连续起止数组、同一闭区间语义、upper_bound、相同行号及未命中规则 |
| 批量与载荷 | 相同 B、输入/输出复用策略、输出顺序、字段投影及 Java 侧可消费结果表示 |
| 快照与刷新 | 同等不可变快照、租约、close 语义、版本一致性和失败保留旧版本能力；允许各自采用语言适合的实现 |
| 优化与线程 | 基础二分配对比较；前缀目录、排序双指针和并行优化配对比较；相同总线程预算 |
| 测量范围 | 查询、加载/构建、刷新与峰值内存分别对比；业务调用路径均包含实际库管理成本 |

纯 Java 库不需要人为增加 JNI 对应的数组复制；JNI 方案所需复制是其真实成本。同样不要求 Java 模仿 Rust 的句柄表或引用计数实现，只要求行为、安全保证与资源使用边界可比。Java 堆内索引和 Native 索引的资源对比应包含各自进程 RSS、Java 堆/GC 与 Native 分配，不能只比较数组理论容量。

附加控制组：Java 库内部 primitive array 查询内核、JNI no-op、仅数组搬运、仅载荷提取、查找加真实载荷。控制组用于解释成本，时间相减受噪声影响，不能当作精确分解。JMH 用于 Java/JNI 同框架比较；Criterion 用于 Rust core 的算法实验；跨框架绝对数字需用同样工作量的整体计时校核。[JMH 官方仓库](https://github.com/openjdk/jmh)、[Criterion 文档](https://bheisler.github.io/criterion.rs/book/)

Java 可用 `Integer.compareUnsigned`；也可在加载时将全部 starts/ends 都异或 `Integer.MIN_VALUE` 后以有符号比较，查询值做同样转换。不能只转换查询值却保留原始端点。两种 Java 实现都应先验证正确性，再选择较好且可维护者作为对照。

### 17.2 数据与正确性 oracle

主规模固定 `N=10M`、`Q=1M`。每个 case 保存随机种子、数据文件 hash、查询序列 hash、实际命中率、宽度与空洞分布。IPv4 生成器需保证 N 个有序不重叠区间能放入 `2^32` 地址空间，不能独立随机生成端点后直接声称无重叠。

使用同一份预生成二进制输入供三个实现读取，避免语言 PRNG 不同。小规模数据使用简单线性扫描作为独立 oracle；大规模使用可数学推导的合成区间和预期结果，并辅以边界样本及三实现全量交叉校验。不能只让三个同源二分实现彼此验证。

### 17.3 测试矩阵

| 维度 | 必测取值/场景 |
| --- | --- |
| 批次 B | 1、32、256、1,024、4,096、16,384、65,536、1,000,000；最后不足批按实际长度 |
| 外部并发 C | 1、2、4、8、16、32；超出物理核数仍单独标注；64 可作为高并发扩展 |
| 查询分布 | 全地址均匀、热点集中、已排序、重复值、真实脱敏轨迹 |
| 命中率 | 0%、50%、90%、100%；记录实际值，区分控制命中率与全地址均匀抽样 |
| 数据分布 | 均匀短区间、宽度偏斜、空洞多、跨前缀长区间 |
| 算法 | 基础二分；前缀；排序双指针；按需并行单独实验 |
| 载荷 | 仅行号、固定数值字段、真实变长字符串字段 |
| 运行状态 | 稳态、刷新同时发生、旧快照长时间被租约持有 |

分阶段执行以控制矩阵规模：先固定 90% 命中、随机顺序、基础二分，对全部 B×C 做基线；再对 B=1/1,024/65,536/1M 与 C=1/8/32 交叉运行所有分布/命中/缓存场景；最后对候选生产配置覆盖真实载荷、刷新与平台。明确记录未跑组合，不能称为完整笛卡尔积覆盖。

Q=1M 定义为每个逻辑工作单元的总查询数，并发线程分摊该工作量；稳态测试重复工作单元以积累足够样本。另测“每客户端 Q=1M”时，总工作量为 C×Q，必须单列。吞吐按实际处理 IP 数计算，不能把批次数当作 IP QPS。

### 17.4 缓存状态与计时边界

1. 热态：JIT 和分配路径预热后，多轮使用固定查询序列；注明 80 MB 索引不一定能完全驻留 LLC，热态不等于所有数据在 CPU cache。
2. CPU cache 扰动态：在目标 case 外访问大于已知 LLC 的独立缓冲区，尽力扰动缓存；扰动操作不计入查询时间，但其方法和频率必须披露。不能声称确保每次访问都冷。
3. 文件 page cache 热/冷：只用于加载性能实验，与查询缓存分开。冷文件缓存需要独立测试环境、重启或经验证的清理方法；新 JVM 并不自动清除 OS page cache。无法确认则标“缓存状态未受控”。
4. 首次使用：单列动态库加载、Parquet 读取/解压/校验、构建、首批查询与 readiness 时间，不混入稳态纯查询指标。

### 17.5 方法与统计

建议起始配置为 JMH 3 个独立 fork、每 fork 5×1s warmup 和 10×1s measurement；若 JIT/吞吐尚未稳定则延长。Rust core 也要预触达数据并设置足够 measurement 时间。上述只是实验起点，不是已执行参数。

预生成输入在计时外，排序等算法必需工作在计时内；输出预分配模式与每请求分配模式分开测。消费输出 checksum 或 Blackhole，防止死代码消除；校验结果在独立正确性阶段进行。记录完整版本、编译参数、JVM GC/堆参数、CPU 核数、频率策略、LLC、NUMA、RAM、OS、libc、JDK 发行版和后台负载。

记录 IP/s、batch/s、批次延迟 P50/P95/P99、CPU 时间/IP、RSS/峰值 RSS、Java allocation/GC pause、Native 分配/残留内存，能采集时增加 LLC miss 与 branch miss。批次耗时除以 B 只能叫平均摊销 ns/IP，不能叫单 IP P99。Rust core 的迭代统计也不能直接解释为线上请求尾延迟。

尾延迟使用请求级时间戳和直方图，重复收集足够批次，例如至少 100,000 个请求样本，并披露样本数与置信区间。超大批次若时间预算无法达到该数量，应明确 P99 置信不足。在线负载额外使用受控到达率的压测，将排队时间纳入延迟，避免只用闭环吞吐掩盖排队。

比较使用同机交替运行或随机化顺序，报告多轮区间，不只挑最好一轮。执行新算法时给 Java 同等算法机会；不把并行 Rust 与单线程 Java、纯行号与字符串载荷、热态与冷态混在同一收益比中。

### 17.6 报告模板

每行至少包含：caseId、数据/查询 hash、N/Q/B/C、算法、载荷模式、缓存模式、总 IP 数、重复次数、IP/s、batch P99、CPU/IP、峰值 RSS、刷新耗时、错误数、置信区间、环境 ID。当前所有实测值为空，待实施后填写；本文没有运行上述 Benchmark。

## 18. 验收指标与退出条件

### 18.1 必须通过的功能与安全门槛

| 验收项 | 通过条件 | 证据 |
| --- | --- | --- |
| 结果正确性 | 确定性边界集与随机属性测试零不一致；N=10M/Q=1M 三实现输出一致；Java 基线库与 JNI 库通过相同批量、载荷、快照和刷新契约测试 | 数据 seed/hash、预期结果和测试日志 |
| 数据验证 | null、越界、重叠、跨 batch/文件乱序全部拒绝；坏版本不得替换好版本 | 负例 fixture、activeVersion 前后记录 |
| 并发生命周期 | query/close/acquire/refresh 竞争无 UAF、重复释放或版本混用 | 压测、sanitizer 可用平台报告、错误码统计 |
| 刷新一致性 | 同租约所有结果和载荷来自同版本；失败仍服务旧版本 | 双版本确定性测试 |
| 内存回收 | 租约释放后旧快照计数归零；重复加载释放无持续增长的 live allocation | 分配跟踪；RSS 分配器留存单独解释 |
| Java 兼容 | JDK 11 编译和真实运行，ABI 错配可靠拒绝 | 对应平台 smoke/integration 日志 |
| 分发 | 所有声明支持的平台成功加载并完成查询/关闭/刷新 | CI 制品 hash 与目标系统测试结果 |

建议资源测试执行至少 100 次加载/切换/释放循环及 60 分钟并发稳定性测试；这些是待执行验收计划，不是当前测试结果。FFI 负例仅注入可控合法内存中的错误参数，不在 JVM 主测试进程中任意解引用伪造地址；崩溃类实验放独立子进程。

### 18.2 性能与资源门槛

绝对 SLO 尚未给出，不能把任意 QPS 或“快 20%”写成业务要求。评审时必须填写 `T_min（IP/s）`、`L99_max（指定 B/C 的批次 P99）`、`RSS_max`、`Load_max`、`Refresh_max` 与典型 payload；未填写且未实测时，性能验收状态为待验证。

建议试点决策规则：JNI 端到端达到上述业务门槛，且相对采用同等设计、完成相应优化的 Java 基线库的收益足以抵偿维护成本。可讨论采用“主场景吞吐提升至少 15%，P99 回退不超过 5%”作为项目内部候选门槛，但该数值不是用户已批准要求，也不是性能预测；必须连同测量误差和其他资源收益一起评审。若主要收益是可复用 Native 核心而非速度，也应单独说明成本依据。

若 JNI 不满足目标、复制耗时吞掉收益或平台维护成本过高，停止扩大 Native 范围，保留 Java 基线作为退路；不得为了证明 Rust 更快删除不利样本。算法优化只有在正确性门槛持续通过且目标场景实测收益明确时才默认开启。

## 19. 实施批次与交付物

| 批次 | 范围 | 退出条件 |
| --- | --- | --- |
| P0 契约确认 | 样本/schema、平台、载荷、IPv6 范围、SLO、版本策略 | 待决策项形成明确记录 |
| P1 基础 core | 同等设计的 Java 基线库、Rust 二分、Parquet 校验、fixture | 正确性通过，内存预算可接受 |
| P2 边界与接入 | C ABI、JNI、Java 11 API、句柄/close | 生命周期与错误映射测试通过 |
| P3 刷新与分发 | 快照版本、刷新、回滚、平台 JAR | 目标平台运行及刷新测试通过 |
| P4 性能决策 | 规定矩阵、可选优化、业务载荷实测 | 正式报告决定启用项与是否采用 |

最终实现交付应包括源码及锁文件、版本化 C 头文件、Java API JAR、Native classifier JAR、manifest/schema 规范、构建与发布说明、生命周期/错误契约、同等设计的 Java 基线库源码与构建说明、数据生成器、Benchmark 原始结果及分析、许可证/SBOM、回滚指南。本文交付的是技术设计，不包含已编译库、实际 CI 或生产部署。

## 20. 风险与待决策事项

| 风险/决策 | 当前建议 | 需要的确认或验证 |
| --- | --- | --- |
| 真实 Parquet schema 未提供 | 显式适配，严格拒绝未知映射 | bd/ud 类型、null、文件组织、codec、样本 |
| 返回完整记录的成本未知 | 行号与批量列式载荷分离测量 | 字段类型、平均/最大字符串长度、是否需稳定 ID |
| IPv6 范围未确认 | 独立设计，默认首批 IPv4 | 首版是否支持、mapped 地址规则 |
| Native 故障影响 JVM | 缩小 unsafe、边界测试、隔离崩溃实验 | 业务是否接受同进程故障域 |
| JNI 复制及批次等待 | 首版显式复制、以 B×C 实测选参数 | 延迟目标、最大批次、在线/离线比例 |
| 热更新峰值和长租约 | 限制刷新并发、版本留存准入 | 主机内存、最大租约时长、刷新频率 |
| 多 JVM 重复索引 | 每 JVM 独立预算 | 实例密度是否可接受 |
| 供应链与平台数量 | 明确支持矩阵、锁版本、真实机器测试 | OS/CPU/libc/最低系统版本 |
| 数据发布损坏或回滚 | 不可变 manifest、checksum、旧版本保留 | 发布方责任、数据保留策略 |
| classloader/热重载 | 单一加载器、固定 Native 版本 | 是否部署应用服务器/插件容器 |
| 性能采用标准 | 以端到端业务 SLO 为先 | T_min/L99/RSS/加载时间与可接受维护成本 |

## 21. 替代方案（不进入主架构）

采用相同设计思想的纯 Java 库：是正式基线与可交付的替代方案，同样具有 Parquet 加载、连续基本类型数组、区间查找、批量 API、不可变快照和版本切换能力。若性能足够且无跨语言复用需求，可考虑直接采用该库。primitive array 查询内核是其组成部分，库级端到端结果是采用判断的依据。

Redis 查询：可集中维护数据并供多服务访问，但增加协议、网络、服务端调度与集中资源管理问题；不作为本设计的查询或版本协调依赖。是否采用应另作服务化需求与容量评估。

Rust Sidecar：可在多个进程间复用独立服务并隔离部分故障域，但增加 IPC、部署、序列化与运维成本；不与本方案的 JNI 热路径混搭。未来若同机多 JVM 内存重复成为主要问题，可另立设计。

## 22. 资料与交付状态

本文技术事实参考 Rust 标准库、Rust Reference、Apache Arrow/Parquet、Java SE 11 JNI/API 和所选工具的官方文档；文中各相关章节提供直接链接。访问日期为 2026-11-04。官方 API 能力支持设计可行性，不构成本业务性能证明；latest 文档链接不替代实现时的版本锁定。

本次已完成：原讨论背景核对、主架构与边界设计、API 草案、数据与生命周期契约、构建分发路线、刷新策略、性能实验与验收计划。尚未完成且不作完成声明：代码实现、真实 Parquet 校验、Java/Rust 编译、JNI 运行、跨平台测试、Benchmark、发布与部署。
