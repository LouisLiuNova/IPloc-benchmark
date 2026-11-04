package io.iploc.bench;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.*;

/** 探索性全矩阵：每个进程只加载一套快照，JMH non-forked，共享 JIT/GC 历史。 */
public final class QuickMatrixMain {
  public static void main(String[] args) throws Exception {
    String group = args[0], dataset = args[1];
    Path output = Path.of(args[2]);
    boolean prefix = group.equals("prefix");
    LookupBenchmark.quickSnapshot = Candidates.open("java", dataset, prefix);
    try {
      if (group.equals("real")) {
        run(
            output,
            "c1",
            dataset,
            false,
            1,
            new String[] {"1024", "4096", "65536", "1", "256", "1000000"},
            "rows|utf8|strings",
            new String[] {"100"});
        for (int threads : new int[] {4, 8})
          run(
              output,
              "c" + threads,
              dataset,
              false,
              threads,
              new String[] {"1024", "4096", "65536"},
              "rows|utf8|strings",
              new String[] {"100"});
        run(
            output,
            "parsed",
            dataset,
            false,
            1,
            new String[] {"4096"},
            "parsed",
            new String[] {"100"});
      } else if (group.equals("synthetic")) {
        for (int threads : new int[] {1, 4})
          run(
              output,
              "c" + threads,
              dataset,
              false,
              threads,
              new String[] {"4096"},
              "rows|utf8|strings",
              new String[] {"0", "50", "90"});
      } else if (prefix) {
        for (int threads : new int[] {1, 4, 8})
          run(
              output,
              "c" + threads,
              dataset,
              true,
              threads,
              new String[] {"1024", "4096", "65536"},
              "rows",
              new String[] {"100"});
      } else throw new IllegalArgumentException("Unknown quick group: " + group);
    } finally {
      LookupBenchmark.quickSnapshot.close();
      LookupBenchmark.quickSnapshot = null;
    }
  }

  private static void run(
      Path output,
      String name,
      String dataset,
      boolean prefix,
      int threads,
      String[] batches,
      String paths,
      String[] hits)
      throws Exception {
    Options options =
        new OptionsBuilder()
            .include(".*LookupBenchmark.(" + paths + ")$")
            .param("candidate", "java")
            .param("dataset", dataset)
            .param("prefix", String.valueOf(prefix))
            .param("batch", batches)
            .param("hitRate", hits)
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
            .result(output.resolve("jmh-" + name + ".json").toString())
            .build();
    new Runner(options).run();
  }
}
