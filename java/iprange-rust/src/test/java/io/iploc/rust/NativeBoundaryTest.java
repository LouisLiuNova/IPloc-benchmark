package io.iploc.rust;

import static org.junit.jupiter.api.Assertions.*;

import io.iploc.api.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeBoundaryTest {
  private final Path valid = Path.of("../../tests/fixtures/valid.parquet");

  @Test
  void viewsResultsAndRepeatedLifecycle() throws Exception {
    for (int i = 0; i < 100; i++) {
      RustIpRangeSnapshot base = RustIpRangeSnapshot.open(valid);
      try (RustIpRangeSnapshot view =
          base.withAlgorithm(
              RustIpRangeSnapshot.ALGORITHMS.get(i % RustIpRangeSnapshot.ALGORITHMS.size()))) {
        base.close();
        base.close();
        PayloadBatch result = view.fetchBatch(new long[] {2, -1}, 0, 2);
        result.columns[0].data[0] = 0;
        assertEquals("中国-country", view.fetchBatch(new long[] {2}, 0, 1).columns[0].stringAt(0));
        assertEquals(4, view.rowCount());
      }
    }
  }

  @Test
  void invalidHandlesAndParameters() throws Exception {
    assertEquals(1, NativeBridge.abiVersion());
    assertThrows(IllegalStateException.class, () -> NativeBridge.version(Long.MAX_VALUE));
    assertThrows(
        IllegalStateException.class,
        () -> NativeBridge.lookup(0, new int[0], 0, 0, new long[0], 0));
    assertThrows(IllegalArgumentException.class, () -> RustIpRangeSnapshot.open(valid, "unknown"));
    assertThrows(
        java.io.IOException.class,
        () -> RustIpRangeSnapshot.open(valid.resolveSibling("absent.parquet")));
    try (RustIpRangeSnapshot s = RustIpRangeSnapshot.open(valid)) {
      assertThrows(NullPointerException.class, () -> s.fetchBatch(null, 0, 0));
      assertThrows(IndexOutOfBoundsException.class, () -> s.fetchBatch(new long[1], 0, -1));
      assertThrows(
          IndexOutOfBoundsException.class, () -> s.lookupBatch(new int[1], 0, 1, new long[0], 0));
      assertThrows(
          IllegalArgumentException.class, () -> s.fetchBatch(new long[1_000_001], 0, 1_000_001));
      long[] untouched = {123};
      assertThrows(
          IndexOutOfBoundsException.class, () -> s.lookupBatch(new int[1], 0, 1, untouched, 1));
      assertEquals(123, untouched[0]);
      int[] ips = new int[1_000_000];
      long[] rows = new long[ips.length];
      Arrays.fill(ips, -1);
      s.lookupBatch(ips, 0, ips.length, rows, 0);
      assertEquals(3, rows[0]);
      assertEquals(3, rows[rows.length - 1]);
    }
  }

  @Test
  void libraryLoadErrorsAreExplicit(@TempDir Path temp) throws Exception {
    probe(null, "absolute native library path");
    probe("relative.so", "absolute native library path");
    probe(temp.resolve("missing.so").toString(), "Can't load library");
    Path source = temp.resolve("wrong.c"), lib = temp.resolve("wrong.so");
    Files.writeString(
        source,
        "int Java_io_iploc_rust_NativeBridge_abiVersion(void *env, void *cls) { return 99; }\n");
    Process cc =
        new ProcessBuilder("cc", "-shared", "-fPIC", source.toString(), "-o", lib.toString())
            .inheritIO()
            .start();
    assertEquals(0, cc.waitFor());
    probe(lib.toString(), "ABI version mismatch");
  }

  private void probe(String library, String message) throws Exception {
    List<String> cmd = new ArrayList<>();
    cmd.add(Path.of(System.getProperty("java.home"), "bin/java").toString());
    if (library != null) cmd.add("-Diploc.native.path=" + library);
    cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), Probe.class.getName()));
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    String output =
        new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertNotEquals(0, p.waitFor(), output);
    assertTrue(output.contains(message), output);
  }

  public static class Probe {
    public static void main(String[] args) {
      NativeBridge.abiVersion();
    }
  }
}
