package io.iploc.api;

/** 不可变 IPv4 快照。行号只属于产生它的快照；调用期间不得并发修改输入或输出区间。 */
public interface IpRangeSnapshot extends AutoCloseable {
  int MAX_BATCH = 1_000_000;

  String datasetVersion();

  long rowCount();

  void lookupBatch(int[] ips, int inputOffset, int length, long[] rows, int outputOffset);

  PayloadBatch fetchBatch(long[] rows, int offset, int length);

  @Override
  void close();

  default StringLookup lookupStrings(String[] ips, int offset, int length) {
    BatchChecks.range(ips.length, offset, length);
    int[] values = new int[length];
    boolean[] valid = new boolean[length];
    for (int i = 0; i < length; i++) {
      Long parsed = Ipv4.parse(ips[offset + i]);
      valid[i] = parsed != null;
      if (valid[i]) values[i] = (int) (long) parsed;
    }
    long[] rows = new long[length];
    lookupBatch(values, 0, length, rows, 0);
    StringLookup.Status[] statuses = new StringLookup.Status[length];
    for (int i = 0; i < length; i++) {
      if (!valid[i]) {
        rows[i] = -1;
        statuses[i] = StringLookup.Status.INVALID_INPUT;
      } else
        statuses[i] = rows[i] < 0 ? StringLookup.Status.NOT_FOUND : StringLookup.Status.MATCHED;
    }
    return new StringLookup(statuses, rows, fetchBatch(rows, 0, length));
  }
}
