package io.iploc.bench;

import io.iploc.api.IpRangeSnapshot;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.*;

/** 每进程仅一个候选与算法；正确性、预检和主矩阵共享输入生成与消费。 */
public final class CandidateRunMain {
  public static void main(String[] args) throws Exception {
    String action = args[0], candidate = args[1], algorithm = args[2], dataset = args[3];
    Path output = Path.of(args[4]);
    long begin = System.nanoTime();
    try (IpRangeSnapshot snapshot = Candidates.open(candidate, dataset, algorithm, false)) {
      double load = (System.nanoTime() - begin) / 1e9;
      // 索引构建独立计时，同时证明策略视图无需重载数据。
      begin = System.nanoTime();
      try (IpRangeSnapshot view = CandidateSnapshot.view(snapshot, algorithm)) {
        Files.writeString(
            output.resolve("index-" + algorithm + ".json"),
            "{\"candidate\":\""
                + candidate
                + "\",\"algorithm\":\""
                + algorithm
                + "\",\"seconds\":"
                + (System.nanoTime() - begin) / 1e9
                + ",\"primitiveIndexBytes\":"
                + CandidateSnapshot.indexBytes(view)
                + ",\"loadSeconds\":"
                + load
                + "}");
      }
      int[] input = LookupBenchmark.Dataset.queries(snapshot, dataset, 100);
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      ByteBuffer bytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
      for (int ip : input) {
        bytes.clear();
        bytes.putInt(ip);
        digest.update(bytes.array());
      }
      StringBuilder hash = new StringBuilder();
      for (byte b : digest.digest()) hash.append(String.format("%02x", b & 255));
      Files.writeString(output.resolve("query-sha256.txt"), hash.toString() + "\n");
      if (action.equals("verify")) {
        VerifyMain.verify(snapshot, Path.of(args[5]));
        return;
      }
      if (action.equals("synthetic")) {
        verifySynthetic(snapshot);
        return;
      }
      LookupBenchmark.quickSnapshot = snapshot;
      try {
        boolean quick = action.equals("preflight");
        for (int threads : quick ? new int[] {1} : new int[] {1, 4, 8}) {
          Options options =
              new OptionsBuilder()
                  .include(
                      quick
                          ? ".*LookupBenchmark.(rows|utf8|strings|parsed)$"
                          : ".*LookupBenchmark.(rows|utf8|strings)$")
                  .param("candidate", candidate)
                  .param("dataset", dataset)
                  .param("algorithm", algorithm)
                  .param("prefix", String.valueOf(algorithm.startsWith("prefix")))
                  .param(
                      "batch",
                      quick ? new String[] {"4096"} : new String[] {"1024", "4096", "65536"})
                  .param("hitRate", "100")
                  .threads(threads)
                  .forks(0)
                  .warmupIterations(1)
                  .warmupTime(TimeValue.seconds(1))
                  .measurementIterations(1)
                  .measurementTime(TimeValue.seconds(1))
                  .mode(Mode.Throughput)
                  .mode(Mode.SampleTime)
                  .timeUnit(TimeUnit.SECONDS)
                  .shouldFailOnError(true)
                  .addProfiler("gc")
                  .resultFormat(ResultFormatType.JSON)
                  .result(output.resolve("jmh-c" + threads + ".json").toString())
                  .build();
          new Runner(options).run();
        }
      } finally {
        LookupBenchmark.quickSnapshot = null;
      }
    }
  }

  private static void verifySynthetic(IpRangeSnapshot snapshot) {
    for (int hit : new int[] {0, 50, 90, 100}) {
      int[] ips =
          LookupBenchmark.Dataset.queries(snapshot, "synthetic:" + snapshot.rowCount(), hit);
      long[] rows = new long[ips.length];
      snapshot.lookupBatch(ips, 0, ips.length, rows, 0);
      int actual = 0;
      for (int i = 0; i < ips.length; i++) {
        long value = Integer.toUnsignedLong(ips[i]);
        long expected = value / 256 < snapshot.rowCount() && value % 256 < 128 ? value / 256 : -1;
        if (expected != rows[i]) throw new AssertionError("Synthetic mismatch at " + i);
        if (expected >= 0) actual++;
      }
      if (actual != ips.length / 100 * hit) throw new AssertionError("Hit rate mismatch");
      // 大批次真实提取八字段，校验未命中和字段内容。
      io.iploc.api.PayloadBatch payload = snapshot.fetchBatch(rows, 0, 4096);
      for (int i = 0; i < 4096; i++)
        for (int f = 0; f < 8; f++) {
          String expected =
              rows[i] < 0 ? null : "synthetic-" + io.iploc.api.PayloadBatch.FIELDS.get(f);
          if (!Objects.equals(expected, payload.columns[f].stringAt(i)))
            throw new AssertionError("Synthetic payload mismatch");
        }
      System.out.println(
          "SYNTHETIC VERIFIED queries=" + ips.length + " hitRate=" + hit + " matched=" + actual);
    }
  }
}
