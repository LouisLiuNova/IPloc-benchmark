package io.iploc.bench;

import io.iploc.java.*;
import java.nio.file.*;

/** 只加载一套真实数据，逐策略对独立 oracle 校验行号和八字段。 */
public final class VerifyAlgorithmsMain {
  public static void main(String[] args) throws Exception {
    try (JavaIpRangeSnapshot base = (JavaIpRangeSnapshot) Candidates.open("java", args[0], false)) {
      for (SearchAlgorithm algorithm : SearchAlgorithm.values()) {
        try (JavaIpRangeSnapshot view = base.withAlgorithm(algorithm)) {
          System.out.println(
              "VERIFY algorithm=" + algorithm.id() + " indexBytes=" + view.indexBytes());
          VerifyMain.verify(view, Path.of(args[1]));
        }
      }
    }
  }
}
