package io.iploc.bench;

import io.iploc.java.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.*;

/** 算法快速对照：同一数据与载荷，仅替换搜索索引。无内部线程池。 */
public final class QuickAlgorithmsMain {
  public static void main(String[] args) throws Exception {
    Path output = Path.of(args[1]);
    List<SearchAlgorithm> algorithms = new ArrayList<>();
    for (String id : args[2].split(",")) algorithms.add(SearchAlgorithm.fromId(id));
    Collections.shuffle(algorithms, new Random(20261104));
    Files.writeString(output.resolve("order.txt"), algorithms.toString());
    try (JavaIpRangeSnapshot base = (JavaIpRangeSnapshot) Candidates.open("java", args[0], false)) {
      for (SearchAlgorithm algorithm : algorithms) {
        long begin = System.nanoTime();
        try (JavaIpRangeSnapshot view = base.withAlgorithm(algorithm)) {
          double seconds = (System.nanoTime() - begin) / 1e9;
          Files.writeString(
              output.resolve("index-" + algorithm.id() + ".json"),
              "{\"algorithm\":\""
                  + algorithm.id()
                  + "\",\"seconds\":"
                  + seconds
                  + ",\"primitiveIndexBytes\":"
                  + view.indexBytes()
                  + "}");
          System.out.println(
              "INDEX algorithm="
                  + algorithm.id()
                  + " seconds="
                  + seconds
                  + " primitiveBytes="
                  + view.indexBytes());
          LookupBenchmark.quickSnapshot = view;
          for (int threads : new int[] {1, 4, 8}) {
            Options options =
                new OptionsBuilder()
                    .include(".*LookupBenchmark.(rows|utf8|strings)$")
                    .param("candidate", "java")
                    .param("dataset", args[0])
                    .param("algorithm", algorithm.id())
                    .param("prefix", String.valueOf(algorithm.id().startsWith("prefix")))
                    .param("batch", "1024", "4096", "65536")
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
                    .result(
                        output
                            .resolve("jmh-" + algorithm.id() + "-c" + threads + ".json")
                            .toString())
                    .build();
            new Runner(options).run();
          }
        } finally {
          LookupBenchmark.quickSnapshot = null;
        }
      }
    }
  }
}
