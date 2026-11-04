package io.iploc.contract;

import static org.junit.jupiter.api.Assertions.*;

import io.iploc.api.*;
import io.iploc.java.JavaIpRangeSnapshot;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

/** 后续候选继承本契约并覆盖 open，即可复用全部行为测试。 */
abstract class SnapshotContract {
  abstract IpRangeSnapshot open(Path file) throws Exception;

  final Path fixtures = Path.of("../../tests/fixtures");

  @Test
  void boundariesPayloadOffsetsAndClose() throws Exception {
    IpRangeSnapshot s = open(fixtures.resolve("valid.parquet"));
    int[] ips = {123, 0, 1, 10, 20, 21, 0x7fffffff, 0x80000000, 0x80000002, 0x80000003, -1, 10};
    long[] actual = new long[13];
    Arrays.fill(actual, 99);
    s.lookupBatch(ips, 1, 11, actual, 1);
    assertArrayEquals(new long[] {99, 0, -1, 1, 1, -1, 2, 2, 2, -1, 3, 1, 99}, actual);
    PayloadBatch p = s.fetchBatch(new long[] {0, 1, 2, 3, -1, 2}, 0, 6);
    assertTrue(p.matched.get(0));
    assertFalse(p.columns[0].valid.get(0));
    assertTrue(p.columns[0].valid.get(1));
    assertEquals("", p.columns[0].stringAt(1));
    for (int f = 0; f < 8; f++)
      assertEquals("中国-" + PayloadBatch.FIELDS.get(f), p.columns[f].stringAt(2));
    assertFalse(p.matched.get(4));
    assertEquals(p.columns[0].stringAt(2), p.columns[0].stringAt(5));
    assertTrue(p.businessRow(2, true).containsKey("IPLOC_CLIENT_ASNUMBER"));
    assertTrue(p.businessRow(2, false).containsKey("IPLOC_SERVER_AREACODE"));
    s.lookupBatch(new int[0], 0, 0, new long[0], 0);
    assertThrows(IllegalArgumentException.class, () -> s.fetchBatch(new long[] {-2}, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> s.fetchBatch(new long[] {4}, 0, 1));
    assertThrows(
        IndexOutOfBoundsException.class, () -> s.lookupBatch(ips, Integer.MAX_VALUE, 1, actual, 0));
    assertThrows(NullPointerException.class, () -> s.lookupBatch(null, 0, 0, actual, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> s.lookupBatch(new int[1_000_001], 0, 1_000_001, new long[1_000_001], 0));
    s.close();
    s.close();
    assertEquals("中国-country", p.columns[0].stringAt(2));
    assertThrows(
        IllegalStateException.class, () -> s.lookupBatch(new int[0], 0, 0, new long[0], 0));
    assertThrows(IllegalStateException.class, s::datasetVersion);
  }

  @Test
  void parsing() throws Exception {
    for (String bad :
        new String[] {
          null,
          "",
          " 1.2.3.4",
          "1.2.3.4 ",
          "01.2.3.4",
          "1.2.3",
          "256.0.0.0",
          "1.2.3.4:80",
          "1.2.3.4/32",
          "::1",
          "１.2.3.4",
          "1..2.3"
        }) assertNull(Ipv4.parse(bad), String.valueOf(bad));
    assertEquals(0xffff_ffffL, Ipv4.parse("255.255.255.255"));
    try (IpRangeSnapshot s = open(fixtures.resolve("valid.parquet"))) {
      StringLookup r =
          s.lookupStrings(new String[] {"0.0.0.0", "bad", "0.0.0.1", "255.255.255.255"}, 0, 4);
      assertArrayEquals(
          new StringLookup.Status[] {
            StringLookup.Status.MATCHED,
            StringLookup.Status.INVALID_INPUT,
            StringLookup.Status.NOT_FOUND,
            StringLookup.Status.MATCHED
          },
          r.statuses);
    }
  }

  @Test
  void emptyAndChunks() throws Exception {
    try (IpRangeSnapshot s = open(fixtures.resolve("empty.parquet"))) {
      long[] r = {99};
      s.lookupBatch(new int[] {-1}, 0, 1, r, 0);
      assertEquals(-1, r[0]);
    }
    try (IpRangeSnapshot s = open(fixtures.resolve("chunks.parquet"))) {
      int[] ips = {39999, 40000, 65535, 65536, 65537, 65538};
      long[] r = new long[6];
      s.lookupBatch(ips, 0, 6, r, 0);
      assertArrayEquals(new long[] {39999, 40000, 65535, 65536, 65537, -1}, r);
      assertEquals("x", s.fetchBatch(r, 0, 6).columns[7].stringAt(4));
    }
  }

  @Test
  void longRangesCrossPrefixes() throws Exception {
    try (IpRangeSnapshot s = open(fixtures.resolve("wide.parquet"))) {
      int[] ips = {1, 65535, 65536, 0x7fffffff, 0x80000000, 0x80000001, 0xfffeffff, 0xffff0000, -1};
      long[] rows = new long[ips.length];
      s.lookupBatch(ips, 0, ips.length, rows, 0);
      assertArrayEquals(new long[] {0, 0, 0, 0, 0, -1, -1, 1, 1}, rows);
    }
  }

  @Test
  void refusesBadData() {
    for (String name :
        List.of(
            "overlap",
            "reversed",
            "negative",
            "overflow",
            "null",
            "cross-group",
            "wrong-type",
            "wrong-payload",
            "missing"))
      assertThrows(Exception.class, () -> open(fixtures.resolve(name + ".parquet")), name);
  }

  @Test
  void independentLinearOracleAndCloseRace() throws Exception {
    IpRangeSnapshot s = open(fixtures.resolve("valid.parquet"));
    long[][] ranges = {{0, 0}, {10, 20}, {2147483647L, 2147483650L}, {4294967295L, 4294967295L}};
    Random random = new Random(20261104L);
    int[] ips = new int[10000];
    long[] r = new long[ips.length];
    for (int i = 0; i < ips.length; i++) ips[i] = random.nextInt();
    s.lookupBatch(ips, 0, ips.length, r, 0);
    for (int i = 0; i < ips.length; i++) {
      long v = Integer.toUnsignedLong(ips[i]), expected = -1;
      for (int j = 0; j < ranges.length; j++)
        if (v >= ranges[j][0] && v <= ranges[j][1]) expected = j;
      assertEquals(expected, r[i]);
    }
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<?>> calls = new ArrayList<>();
      for (int k = 0; k < 4; k++)
        calls.add(
            pool.submit(
                () -> {
                  for (int j = 0; j < 100; j++) {
                    try {
                      s.lookupBatch(ips, 0, ips.length, new long[ips.length], 0);
                    } catch (IllegalStateException expected) {
                      break;
                    }
                  }
                }));
      s.close();
      for (Future<?> call : calls) call.get(10, TimeUnit.SECONDS);
    } finally {
      s.close();
      pool.shutdownNow();
    }
  }
}

class SnapshotContractTest extends SnapshotContract {
  @Override
  IpRangeSnapshot open(Path p) throws Exception {
    return JavaIpRangeSnapshot.open(p);
  }
}

class PrefixContractTest extends SnapshotContract {
  @Override
  IpRangeSnapshot open(Path p) throws Exception {
    return JavaIpRangeSnapshot.open(p, true);
  }
}

class SyntheticTest {
  @Test
  void arithmeticOracle() {
    for (boolean prefix : new boolean[] {false, true}) {
      try (IpRangeSnapshot s = JavaIpRangeSnapshot.synthetic(1000, prefix)) {
        int[] ips = new int[10000];
        long[] rows = new long[ips.length];
        Random random = new Random(20261104L);
        for (int i = 0; i < ips.length; i++) ips[i] = random.nextInt(300000);
        s.lookupBatch(ips, 0, ips.length, rows, 0);
        for (int i = 0; i < ips.length; i++)
          assertEquals(ips[i] / 256 < 1000 && ips[i] % 256 < 128 ? ips[i] / 256 : -1, rows[i]);
      }
    }
  }
}
