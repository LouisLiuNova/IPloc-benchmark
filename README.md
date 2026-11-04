# IPloc-benchmark

比较同等布局的 Java / Rust IPv4 区间查询候选。主程序目标为 Java 11，真实数据约 3,230 万行。当前 Java 已实现，Rust/JNI 为 `not_implemented`；性能结果仅说明本机 Java 表现。

## 工程与构建

- `java/iprange-api`：快照接口、八字段 UTF-8 结果、严格 IPv4 解析及业务字段适配。
- `java/iprange-java`：Parquet 加载、不可变索引、基础二分及可选 16 位前缀目录。
- `java/iprange-contract`：可继承的共用契约测试与错误数据验证。
- `java/iprange-bench`：候选工厂、独立样本校验和 JMH。
- `rust`：最小 workspace 及后续接入契约，不要求本轮安装 Rust。
- `tools/evaluation`：独立 oracle、串行评估、资源保护和报告汇总。

```bash
# 显式使用 JDK 11；可用 IPLOC_JDK_HOME 指向其他机器的 JDK 11。
./tools/evaluation/build.sh verify
(cd tools/evaluation && python3 -m unittest -v test_runner)
```

Maven Wrapper 固定为 3.9.11 并校验分发包 SHA-256，构建固定 `--release 11`。默认 Wrapper 与依赖缓存位于 `/tmp`，可通过 `IPLOC_MAVEN_HOME`、`IPLOC_MAVEN_REPO` 调整。不要使用默认 Java 版本代替 JDK 11。Linux CI 只运行构建及小数据校验，不运行本机性能矩阵。

## 使用示例

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

## 数据核验与运行

评估脚本需要 Python 3.11+；只有 oracle 和 fixture 生成需要额外 Python 依赖。原始 `iploc.parquet` 放在根目录，文件和大型结果不会纳入 Git。

```bash
uv venv .venv
uv pip install --python .venv/bin/python -r tools/evaluation/requirements.txt
.venv/bin/python tools/evaluation/oracle.py iploc.parquet benchmark-results/oracle
python3 tools/evaluation/run.py verify --oracle benchmark-results/oracle/oracle.tsv --output benchmark-results/verify

# 短预检：1 fork，1 次预热、1 次测量，各 1 秒；不作为正式性能结论。
python3 tools/evaluation/run.py benchmark --quick --candidate all --output benchmark-results/preflight

# 正式矩阵：3 forks，5×1 秒预热、10×1 秒测量，预计耗时数小时。
python3 tools/evaluation/run.py benchmark --output benchmark-results/formal
python3 tools/evaluation/run.py report --output benchmark-results/formal
```

使用 `--jdk` 指定评估 JDK；`--only real-b4096` 可定位场景，`--resume` 只跳过同目录已通过场景，要求代码提交、JAR、数据及 JDK 一致。`--candidate all` 明确跳过尚未实现的 Rust；`--candidate rust-jni` 返回错误。不要把限定场景或短预检称为完整矩阵。

每个目录保存 manifest、命令、控制台、JMH JSON、GC 日志、进程组 RSS/CPU 和系统可用内存采样、状态及汇总报告。测量错误立即停止，不自动重试。资源保护在启动前检查可用内存；运行中每秒采样，连续两次 `MemAvailable < 2 GiB` 或进程组 RSS `> 12 GiB` 时终止当前进程组并停止后续场景。评估 fork 固定 `-Xmx10g`，同一时间仅一套全量快照；不调整系统 swap 或内核参数。

正式矩阵和解释方法见 [评估说明](doc/Java候选与性能评估.md)。已有 [可行性计划](doc/IPv4批量查询可行性验证实施计划.md) 中更大的 Rust/JNI 范围是后续工作，本轮以评估说明为准。
