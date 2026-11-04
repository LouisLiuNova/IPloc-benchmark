package io.iploc.rust;

import io.iploc.api.*;
import java.io.IOException;
import java.lang.ref.Cleaner;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Linux x86_64 Rust/JNI 候选；显式关闭，Cleaner 仅作为遗漏关闭的兜底。 */
public final class RustIpRangeSnapshot implements IpRangeSnapshot {
  public static final List<String> ALGORITHMS =
      List.of(
          "binary",
          "prefix16",
          "prefix20",
          "prefix16-24",
          "interleaved4",
          "interleaved8",
          "prefix16-interleaved4",
          "prefix16-interleaved8",
          "eytzinger");
  private static final Cleaner CLEANER = Cleaner.create();
  private final Lease lease;
  private final Cleaner.Cleanable cleanable;
  private final String algorithm;

  private static final class Lease implements Runnable {
    private final AtomicLong handle;

    Lease(long handle) {
      this.handle = new AtomicLong(handle);
    }

    long acquire() {
      long value = handle.get();
      if (value == 0) throw new IllegalStateException("Snapshot closed");
      return value;
    }

    @Override
    public void run() {
      long value = handle.getAndSet(0);
      if (value != 0) NativeBridge.close(value);
    }
  }

  private RustIpRangeSnapshot(long handle, String algorithm) {
    lease = new Lease(handle);
    Cleaner.Cleanable registered;
    try {
      registered = CLEANER.register(this, lease);
    } catch (Throwable failure) {
      lease.run();
      throw failure;
    }
    cleanable = registered;
    this.algorithm = algorithm;
  }

  public static String nativeBuildInfo() {
    return NativeBridge.buildInfo();
  }

  private static String check(String algorithm) {
    Objects.requireNonNull(algorithm, "algorithm");
    if (!ALGORITHMS.contains(algorithm))
      throw new IllegalArgumentException("Unsupported Rust algorithm: " + algorithm);
    return algorithm;
  }

  public static RustIpRangeSnapshot open(Path file) throws IOException {
    return open(file, "binary");
  }

  public static RustIpRangeSnapshot open(Path file, String algorithm) throws IOException {
    check(algorithm);
    return new RustIpRangeSnapshot(
        NativeBridge.open(file.toAbsolutePath().toString(), algorithm), algorithm);
  }

  public static RustIpRangeSnapshot synthetic(int count, String algorithm) {
    check(algorithm);
    return new RustIpRangeSnapshot(NativeBridge.synthetic(count, algorithm), algorithm);
  }

  public RustIpRangeSnapshot withAlgorithm(String algorithm) {
    check(algorithm);
    try {
      return new RustIpRangeSnapshot(NativeBridge.view(lease.acquire(), algorithm), algorithm);
    } finally {
      java.lang.ref.Reference.reachabilityFence(this);
    }
  }

  public String algorithm() {
    lease.acquire();
    return algorithm;
  }

  public long indexBytes() {
    try {
      return NativeBridge.indexBytes(lease.acquire());
    } finally {
      java.lang.ref.Reference.reachabilityFence(this);
    }
  }

  @Override
  public String datasetVersion() {
    try {
      return NativeBridge.version(lease.acquire());
    } finally {
      java.lang.ref.Reference.reachabilityFence(this);
    }
  }

  @Override
  public long rowCount() {
    try {
      return NativeBridge.rowCount(lease.acquire());
    } finally {
      java.lang.ref.Reference.reachabilityFence(this);
    }
  }

  @Override
  public void lookupBatch(int[] ips, int offset, int length, long[] rows, int out) {
    try {
      NativeBridge.lookup(lease.acquire(), ips, offset, length, rows, out);
    } finally {
      java.lang.ref.Reference.reachabilityFence(this);
    }
  }

  @Override
  public PayloadBatch fetchBatch(long[] rows, int offset, int length) {
    try {
      return NativeBridge.fetch(lease.acquire(), rows, offset, length);
    } finally {
      java.lang.ref.Reference.reachabilityFence(this);
    }
  }

  @Override
  public void close() {
    cleanable.clean();
  }
}
