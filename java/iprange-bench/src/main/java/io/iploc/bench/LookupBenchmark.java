package io.iploc.bench;

import io.iploc.api.*;
import io.iploc.java.JavaIpRangeSnapshot;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.*;

@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
@OutputTimeUnit(TimeUnit.SECONDS)
public class LookupBenchmark {
  // 仅全矩阵快速模式设置；正式 JMH fork 不共享此状态。
  static IpRangeSnapshot quickSnapshot;

  @State(Scope.Benchmark)
  public static class Dataset {
    @Param({"java"})
    public String candidate;

    @Param({"iploc.parquet"})
    public String dataset;

    @Param({"false"})
    public boolean prefix;

    @Param({"legacy"})
    public String algorithm;

    @Param({"100"})
    public int hitRate;

    @Param({"1024", "4096", "65536"})
    public int batch;

    IpRangeSnapshot snapshot;
    int[] ips;

    @Setup(Level.Trial)
    public void setup() throws Exception {
      snapshot =
          quickSnapshot == null
              ? Candidates.open(candidate, dataset, algorithm, prefix)
              : quickSnapshot;
      if (snapshot instanceof JavaIpRangeSnapshot
          && ((JavaIpRangeSnapshot) snapshot).algorithm()
              != Candidates.algorithm(algorithm, prefix))
        throw new IllegalArgumentException(
            "Quick snapshot algorithm does not match JMH parameters");
      ips = new int[1_000_000];
      Random random = new Random(20261104L);
      for (int i = 0; i < ips.length; i++) {
        if (dataset.startsWith("synthetic:")) {
          int row = random.nextInt((int) snapshot.rowCount());
          ips[i] =
              (row << 8) + (i % 100 < hitRate ? random.nextInt(128) : 128 + random.nextInt(128));
        } else ips[i] = random.nextInt();
      }
    }

    @TearDown(Level.Trial)
    public void close() {
      if (snapshot != quickSnapshot) snapshot.close();
    }
  }

  @State(Scope.Thread)
  public static class Worker {
    int[] ips;
    String[] strings;
    long[] rows;
    int cursor;

    @Setup(Level.Trial)
    public void setup(Dataset d, ThreadParams t) {
      int start = d.ips.length * t.getThreadIndex() / t.getThreadCount();
      int end = d.ips.length * (t.getThreadIndex() + 1) / t.getThreadCount();
      ips = Arrays.copyOfRange(d.ips, start, end);
      strings = new String[ips.length];
      rows = new long[d.batch];
      for (int i = 0; i < ips.length; i++) strings[i] = Ipv4.format(ips[i]);
    }

    void lookup(Dataset d) {
      int first = Math.min(d.batch, ips.length - cursor);
      d.snapshot.lookupBatch(ips, cursor, first, rows, 0);
      if (first < d.batch) d.snapshot.lookupBatch(ips, 0, d.batch - first, rows, first);
      cursor = (cursor + d.batch) % ips.length;
    }
  }

  @Benchmark
  public void rows(Dataset d, Worker w, Blackhole bh) {
    w.lookup(d);
    for (long row : w.rows) bh.consume(row);
  }

  @Benchmark
  public void utf8(Dataset d, Worker w, Blackhole bh) {
    w.lookup(d);
    consume(d.snapshot.fetchBatch(w.rows, 0, d.batch), bh);
  }

  @Benchmark
  public void strings(Dataset d, Worker w, Blackhole bh) {
    w.lookup(d);
    String[][] values = d.snapshot.fetchBatch(w.rows, 0, d.batch).toStrings();
    for (String[] row : values) for (String field : row) bh.consume(field);
  }

  @Benchmark
  public void parsed(Dataset d, Worker w, Blackhole bh) {
    int first = Math.min(d.batch, w.strings.length - w.cursor);
    StringLookup a = d.snapshot.lookupStrings(w.strings, w.cursor, first);
    consume(a.payload, bh);
    bh.consume(a.statuses);
    if (first < d.batch) {
      StringLookup b = d.snapshot.lookupStrings(w.strings, 0, d.batch - first);
      consume(b.payload, bh);
      bh.consume(b.statuses);
    }
    w.cursor = (w.cursor + d.batch) % w.strings.length;
  }

  private static void consume(PayloadBatch p, Blackhole bh) {
    bh.consume(p.matched);
    for (PayloadBatch.Column c : p.columns) {
      bh.consume(c.valid);
      bh.consume(c.offsets);
      for (byte value : c.data) bh.consume(value);
    }
  }
}
