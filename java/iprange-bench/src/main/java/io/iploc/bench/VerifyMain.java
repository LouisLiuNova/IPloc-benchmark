package io.iploc.bench;

import io.iploc.api.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class VerifyMain {
  public static void main(String[] args) throws Exception {
    if (args.length < 2) throw new IllegalArgumentException("dataset oracle.tsv [prefix]");
    try (IpRangeSnapshot s =
        Candidates.open("java", args[0], args.length > 2 && Boolean.parseBoolean(args[2]))) {
      List<String> lines = Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8);
      int[] ips = new int[lines.size()];
      long[] rows = new long[ips.length];
      for (int i = 0; i < ips.length; i++)
        ips[i] = (int) Long.parseLong(lines.get(i).split("\t", -1)[0]);
      s.lookupBatch(ips, 0, ips.length, rows, 0);
      PayloadBatch p = s.fetchBatch(rows, 0, rows.length);
      for (int i = 0; i < ips.length; i++) {
        String[] expected = lines.get(i).split("\t", -1);
        if (rows[i] != Long.parseLong(expected[1]))
          throw new AssertionError("rowId mismatch at " + i);
        for (int c = 0; c < 8; c++) {
          String value =
              expected[c + 2].equals("-")
                  ? null
                  : new String(Base64.getDecoder().decode(expected[c + 2]), StandardCharsets.UTF_8);
          if (!Objects.equals(value, p.columns[c].stringAt(i)))
            throw new AssertionError("payload mismatch at " + i + " field=" + c);
        }
      }
      System.out.println(
          "VERIFIED samples="
              + ips.length
              + " rows="
              + s.rowCount()
              + " version="
              + s.datasetVersion());
      System.out.println("CLIENT " + p.businessRow(0, true));
      System.out.println("SERVER " + p.businessRow(0, false));
    }
  }
}
