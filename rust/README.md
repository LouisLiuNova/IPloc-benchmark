# Rust/JNI 候选

Linux x86_64 GNU libc、Java 11 的静态快照评估实现，支持与 Java 相同的九种策略，默认 `binary`。策略语义与索引开销见 [Rust 搜索策略](../doc/Rust搜索策略.md)，本轮实测见[九策略配对报告](../doc/Rust九策略配对快速测量结果.md)，[三策略报告](../doc/Rust与Java配对快速测量结果.md)保留为历史记录。

## 构建和使用

安装 Rustup、C 编译器和 JDK 11。`rust-toolchain.toml` 固定 Rust 1.99.1，`Cargo.lock` 固定依赖。默认 Java 构建无需 Rust；Native 通过 Maven profile 显式启用：

```bash
./tools/evaluation/build.sh -B -Prust-jni verify
(cd rust && cargo fmt --check && cargo clippy --locked --workspace --all-targets -- -D warnings)
(cd rust && cargo test --locked --workspace)
```

Maven 调用 `cargo build --release --locked -p iprange-jni`，生成 `rust/target/release/libiprange_jni.so`。使用标准目标，不设置 `target-cpu=native`。首次 Rustup 工具链及 Maven/Cargo 依赖下载需要网络。

运行 Java 时通过 `-Diploc.native.path=/绝对路径/libiprange_jni.so` 指定库；不支持相对路径或隐式搜索。调用方需依赖 `iprange-api` 和 `iprange-rust` JAR。Native 库缺失、无法加载或 ABI 版本不匹配时明确失败。

```java
try (RustIpRangeSnapshot snapshot = RustIpRangeSnapshot.open(Path.of("iploc.parquet"), "prefix20")) {
    int[] ips = {0x01020304, 0xFFFFFFFF};
    long[] rows = new long[ips.length];
    snapshot.lookupBatch(ips, 0, ips.length, rows, 0);
    PayloadBatch payload = snapshot.fetchBatch(rows, 0, rows.length);
    String[][] values = payload.toStrings();
}
```

`withAlgorithm(String)` 创建共享不可变数据的独立视图，关闭 base 不影响已有视图；`algorithm()` 和 `indexBytes()` 供评估使用。后者只表示额外目录和树布局的基本类型数组字节数，不等于 RSS。`nativeBuildInfo()` 返回动态库编译时的工具链、目标、profile 和十六进制编码的 Rust flags。

## 数据与生命周期契约

- Rust 独立使用 Apache Arrow Rust/Parquet 加载单个不可变文件，只投影两个 INT64 边界列与八个 UTF-8 字段。当前构建启用 Snappy，其他压缩 codec 未纳入本轮支持声明。
- 严格验证必需字段唯一性、类型、null、IPv4 范围、闭区间顺序和不重叠；跨 batch/Row Group 连续校验，禁止重排或裁剪坏数据。加载前后检查文件属性与 SHA-256，调用方须保证加载期间文件不可变。
- 内部为连续 `u32` 起终点、最多 65,536 行一块的 UTF-8 数据、offsets、validity。保留 null、空字符串与未命中的区别。行数和返回列容量遵循 Java 数组限制。
- Java `int` 作为无符号 IPv4 bit pattern；行号从 0 开始，`-1` 未命中。行号仅属于产生它的快照，调用方不得跨数据版本混用裸行号；接口不自动识别来源。
- 单批最大 1,000,000；JNI 使用区域复制，不跨调用保存 Java 数组指针。结果是 Java 自有数据，修改结果或关闭快照不影响其他结果和底层数据。
- 使用单调正整数句柄与 `Arc` 注册表。调用在短锁内取得引用后释放锁；关闭移除句柄，已取得引用的调用可完成，新调用失败。大快照释放发生在注册表锁外。
- 显式关闭幂等，Java `Cleaner` 兜底处理遗漏关闭，不保证触发时机。Rust panic 在 JNI 入口内捕获并映射为 Java 异常；进程 OOM 或系统级中止不属于可恢复 panic。
- 多线程可共享只读快照，输入/输出区间不得并发修改。关闭与字符串便利入口的多个步骤竞争时，允许抛出已关闭异常。
- 单一 classloader、单一 Native 版本；不声明应用服务器热重载、多 classloader 或运行中卸载动态库可用。

## 评估命令

先按根 README 生成或核对真实数据的独立 PyArrow oracle，再执行：

```bash
# 两候选九策略、N=10M/Q=1M、四档命中率的算术 oracle。
python3 tools/evaluation/run.py verify --candidate all --synthetic-verify \
  --output benchmark-results/rust-nine-synthetic-verify

# 同一真实数据的行号和八字段核验。
python3 tools/evaluation/run.py verify --candidate all \
  --oracle benchmark-results/oracle/oracle.tsv --output benchmark-results/rust-nine-real-verify

# 两候选九策略的结果路径及字符串解析入口短预检。
python3 tools/evaluation/run.py benchmark --candidate all --quick \
  --output benchmark-results/rust-nine-preflight

# 972 项快速矩阵；候选和算法在独立进程中串行交替运行。
python3 tools/evaluation/run.py benchmark --candidate all --algorithms-quick \
  --output benchmark-results/rust-nine-java-quick
python3 tools/evaluation/run.py report --output benchmark-results/rust-nine-java-quick
```

可使用 `--candidate rust-jni` 仅测 Native，`--algorithm <ID>` 限定九种策略之一，`--native` 指向其他绝对库路径，`--jdk` 指定 JDK 11。跨候选快速矩阵不接受旧的 `--matrix-quick` 或 `--only`；正式多 fork Native 测量未在本轮开放。旧 Java 九策略命令继续可用。

`--resume` 只跳过相同源码、JAR、Native hash、编译元数据、数据/oracle、输入 hash、JDK 和场景配置下已通过的运行。默认候选仍是 Java；`--candidate all` 需要可用 Native 构建，不再静默跳过 Rust。每个进程只加载一套全量快照，使用原有 2 GiB 系统余量/12 GiB 进程组 RSS 保护。

## 后续待办

九种策略均已接入 Rust；支持列表见 [Rust 搜索策略](../doc/Rust搜索策略.md)。真实数据与大合成数据的独立核验和配对快速测试结果以本轮报告为准。

正式多 fork 长测、30 分钟以上稳定性专项、Native 分配器深入观测、热刷新、C ABI、跨平台 classifier JAR、DirectByteBuffer/零拷贝、内部并行和业务上线均留待后续。本轮不预设加速比，也不把快速矩阵视为生产采用证据。
