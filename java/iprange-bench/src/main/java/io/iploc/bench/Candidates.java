package io.iploc.bench;

import io.iploc.api.IpRangeSnapshot;
import io.iploc.java.JavaIpRangeSnapshot;
import java.nio.file.Path;

public final class Candidates {
  private Candidates() {}

  public static IpRangeSnapshot open(String candidate, String dataset, boolean prefix)
      throws Exception {
    if (!candidate.equals("java"))
      throw new IllegalArgumentException(candidate + ": not_implemented");
    long started = System.nanoTime();
    IpRangeSnapshot result =
        dataset.startsWith("synthetic:")
            ? JavaIpRangeSnapshot.synthetic(Integer.parseInt(dataset.substring(10)), prefix)
            : JavaIpRangeSnapshot.open(Path.of(dataset), prefix);
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
