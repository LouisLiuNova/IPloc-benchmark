package io.iploc.bench;

import io.iploc.api.IpRangeSnapshot;
import io.iploc.java.JavaIpRangeSnapshot;
import io.iploc.java.SearchAlgorithm;
import java.nio.file.Path;

public final class Candidates {
  private Candidates() {}

  public static IpRangeSnapshot open(String candidate, String dataset, boolean prefix)
      throws Exception {
    return open(candidate, dataset, "legacy", prefix);
  }

  public static SearchAlgorithm algorithm(String id, boolean prefix) {
    return "legacy".equals(id)
        ? (prefix ? SearchAlgorithm.PREFIX_16 : SearchAlgorithm.BINARY)
        : SearchAlgorithm.fromId(id);
  }

  public static IpRangeSnapshot open(
      String candidate, String dataset, String algorithm, boolean prefix) throws Exception {
    SearchAlgorithm selected = algorithm(algorithm, prefix);
    if (!candidate.equals("java") && !candidate.equals("rust-jni"))
      throw new IllegalArgumentException("Unknown candidate: " + candidate);
    long started = System.nanoTime();
    IpRangeSnapshot result =
        candidate.equals("rust-jni")
            ? CandidateSnapshot.rust(dataset, selected.id())
            : dataset.startsWith("synthetic:")
                ? JavaIpRangeSnapshot.synthetic(Integer.parseInt(dataset.substring(10)), selected)
                : JavaIpRangeSnapshot.open(Path.of(dataset), selected);
    System.err.println(
        "LOAD rows="
            + result.rowCount()
            + " version="
            + result.datasetVersion()
            + " seconds="
            + (System.nanoTime() - started) / 1e9);
    return result;
  }
}
