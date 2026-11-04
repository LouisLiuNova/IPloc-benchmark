package io.iploc.bench;

import io.iploc.api.IpRangeSnapshot;
import io.iploc.java.*;
import java.lang.reflect.*;
import java.nio.file.Path;

/** 评估专用能力适配；默认构建不依赖 Rust 模块。 */
final class CandidateSnapshot {
  static Object invoke(Object target, String method, Class<?>[] types, Object... args)
      throws Exception {
    try {
      return (target instanceof Class<?> ? (Class<?>) target : target.getClass())
          .getMethod(method, types)
          .invoke(target instanceof Class<?> ? null : target, args);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) throw (Exception) cause;
      if (cause instanceof Error) throw (Error) cause;
      throw new IllegalStateException(cause);
    }
  }

  static IpRangeSnapshot rust(String dataset, String algorithm) throws Exception {
    Class<?> type;
    try {
      type = Class.forName("io.iploc.rust.RustIpRangeSnapshot");
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("rust-jni requires Maven -Prust-jni", e);
    }
    return (IpRangeSnapshot)
        (dataset.startsWith("synthetic:")
            ? invoke(
                type,
                "synthetic",
                new Class<?>[] {int.class, String.class},
                Integer.parseInt(dataset.substring(10)),
                algorithm)
            : invoke(
                type,
                "open",
                new Class<?>[] {Path.class, String.class},
                Path.of(dataset),
                algorithm));
  }

  static String algorithm(IpRangeSnapshot snapshot) throws Exception {
    if (snapshot instanceof JavaIpRangeSnapshot)
      return ((JavaIpRangeSnapshot) snapshot).algorithm().id();
    return (String) invoke(snapshot, "algorithm", new Class<?>[0]);
  }

  static long indexBytes(IpRangeSnapshot snapshot) throws Exception {
    if (snapshot instanceof JavaIpRangeSnapshot)
      return ((JavaIpRangeSnapshot) snapshot).indexBytes();
    return (Long) invoke(snapshot, "indexBytes", new Class<?>[0]);
  }

  static IpRangeSnapshot view(IpRangeSnapshot snapshot, String algorithm) throws Exception {
    if (snapshot instanceof JavaIpRangeSnapshot)
      return ((JavaIpRangeSnapshot) snapshot).withAlgorithm(SearchAlgorithm.fromId(algorithm));
    return (IpRangeSnapshot)
        invoke(snapshot, "withAlgorithm", new Class<?>[] {String.class}, algorithm);
  }
}
