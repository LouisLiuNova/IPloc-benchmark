package io.iploc.api;

public final class BatchChecks {
  private BatchChecks() {}

  public static void range(int capacity, int offset, int length) {
    if (offset < 0 || length < 0 || offset > capacity || length > capacity - offset)
      throw new IndexOutOfBoundsException("Invalid offset/length");
    if (length > IpRangeSnapshot.MAX_BATCH)
      throw new IllegalArgumentException("Batch exceeds 1000000");
  }
}
