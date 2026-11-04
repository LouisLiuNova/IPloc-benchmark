package io.iploc.java;

/** 针对有序、不重叠 IPv4 区间的策略。所有查询均恢复原行号并检查终点。 */
abstract class SearchIndexes {
  abstract void lookup(int[] ips, int offset, int length, long[] rows, int out);

  abstract long bytes();

  static SearchIndexes create(int[] starts, int[] ends, SearchAlgorithm algorithm) {
    if (algorithm == SearchAlgorithm.EYTZINGER) return new Eytzinger(starts, ends);
    if (algorithm.lanes > 1) return new Interleaved(starts, ends, algorithm);
    return new Sorted(starts, ends, algorithm);
  }

  static class Sorted extends SearchIndexes {
    final int[] starts, ends, directory;
    final int[][] second;
    final int shift;

    Sorted(int[] starts, int[] ends, SearchAlgorithm algorithm) {
      this.starts = starts;
      this.ends = ends;
      shift = 32 - algorithm.prefixBits;
      directory = algorithm.prefixBits == 0 ? null : new int[(1 << algorithm.prefixBits) + 1];
      if (directory != null) {
        int row = 0;
        for (int p = 0; p < directory.length; p++) {
          long min = (long) p << shift;
          while (row < starts.length && Integer.toUnsignedLong(starts[row]) < min) row++;
          directory[p] = row;
        }
      }
      second = algorithm.adaptive ? new int[65536][] : null;
      if (second != null) {
        for (int p = 0; p < 65536; p++) {
          // 仅按区间起点密度细分；跨桶长区间始终通过全局前驱候选保留。
          if (directory[p + 1] - directory[p] < 4096) continue;
          int[] sub = new int[257];
          int row = directory[p];
          for (int q = 0; q <= 256; q++) {
            long min = ((long) p << 16) + ((long) q << 8);
            while (row < directory[p + 1] && Integer.toUnsignedLong(starts[row]) < min) row++;
            sub[q] = row;
          }
          second[p] = sub;
        }
      }
    }

    final long bounds(int ip) {
      if (directory == null) return starts.length;
      int p = ip >>> shift;
      int[] sub = second == null ? null : second[p];
      int lo, hi;
      if (sub == null) {
        lo = directory[p];
        hi = directory[p + 1];
      } else {
        int q = (ip >>> 8) & 255;
        lo = sub[q];
        hi = sub[q + 1];
      }
      return ((long) Math.max(0, lo - 1) << 32) | hi;
    }

    final long match(int row, int ip) {
      return row >= 0 && Integer.compareUnsigned(ip, ends[row]) <= 0 ? row : -1;
    }

    @Override
    void lookup(int[] ips, int offset, int length, long[] rows, int out) {
      for (int j = 0; j < length; j++) {
        int ip = ips[offset + j];
        long range = bounds(ip);
        int lo = (int) (range >>> 32), hi = (int) range;
        while (lo < hi) {
          int mid = lo + ((hi - lo) >>> 1);
          if (Integer.compareUnsigned(starts[mid], ip) <= 0) lo = mid + 1;
          else hi = mid;
        }
        rows[out + j] = match(lo - 1, ip);
      }
    }

    @Override
    long bytes() {
      long bytes = directory == null ? 0 : 4L * directory.length;
      if (second != null) for (int[] sub : second) if (sub != null) bytes += 4L * sub.length;
      return bytes; // 只统计额外基本类型数组，不含对象头及引用。
    }
  }

  static final class Interleaved extends Sorted {
    final int lanes;

    Interleaved(int[] starts, int[] ends, SearchAlgorithm algorithm) {
      super(starts, ends, algorithm);
      lanes = algorithm.lanes;
    }

    @Override
    void lookup(int[] ips, int offset, int length, long[] rows, int out) {
      int done =
          lanes == 4 ? four(ips, offset, length, rows, out) : eight(ips, offset, length, rows, out);
      super.lookup(ips, offset + done, length - done, rows, out + done);
    }

    private int four(int[] ips, int offset, int length, long[] rows, int out) {
      int j = 0;
      for (; j <= length - 4; j += 4) {
        int v0 = ips[offset + j + 0];
        long b0 = bounds(v0);
        int l0 = (int) (b0 >>> 32), h0 = (int) b0;
        int v1 = ips[offset + j + 1];
        long b1 = bounds(v1);
        int l1 = (int) (b1 >>> 32), h1 = (int) b1;
        int v2 = ips[offset + j + 2];
        long b2 = bounds(v2);
        int l2 = (int) (b2 >>> 32), h2 = (int) b2;
        int v3 = ips[offset + j + 3];
        long b3 = bounds(v3);
        int l3 = (int) (b3 >>> 32), h3 = (int) b3;
        while (l0 < h0 || l1 < h1 || l2 < h2 || l3 < h3) {
          if (l0 < h0) {
            int m = l0 + ((h0 - l0) >>> 1);
            if (Integer.compareUnsigned(starts[m], v0) <= 0) l0 = m + 1;
            else h0 = m;
          }
          if (l1 < h1) {
            int m = l1 + ((h1 - l1) >>> 1);
            if (Integer.compareUnsigned(starts[m], v1) <= 0) l1 = m + 1;
            else h1 = m;
          }
          if (l2 < h2) {
            int m = l2 + ((h2 - l2) >>> 1);
            if (Integer.compareUnsigned(starts[m], v2) <= 0) l2 = m + 1;
            else h2 = m;
          }
          if (l3 < h3) {
            int m = l3 + ((h3 - l3) >>> 1);
            if (Integer.compareUnsigned(starts[m], v3) <= 0) l3 = m + 1;
            else h3 = m;
          }
        }
        rows[out + j + 0] = match(l0 - 1, v0);
        rows[out + j + 1] = match(l1 - 1, v1);
        rows[out + j + 2] = match(l2 - 1, v2);
        rows[out + j + 3] = match(l3 - 1, v3);
      }
      return j;
    }

    private int eight(int[] ips, int offset, int length, long[] rows, int out) {
      int j = 0;
      for (; j <= length - 8; j += 8) {
        int v0 = ips[offset + j + 0];
        long b0 = bounds(v0);
        int l0 = (int) (b0 >>> 32), h0 = (int) b0;
        int v1 = ips[offset + j + 1];
        long b1 = bounds(v1);
        int l1 = (int) (b1 >>> 32), h1 = (int) b1;
        int v2 = ips[offset + j + 2];
        long b2 = bounds(v2);
        int l2 = (int) (b2 >>> 32), h2 = (int) b2;
        int v3 = ips[offset + j + 3];
        long b3 = bounds(v3);
        int l3 = (int) (b3 >>> 32), h3 = (int) b3;
        int v4 = ips[offset + j + 4];
        long b4 = bounds(v4);
        int l4 = (int) (b4 >>> 32), h4 = (int) b4;
        int v5 = ips[offset + j + 5];
        long b5 = bounds(v5);
        int l5 = (int) (b5 >>> 32), h5 = (int) b5;
        int v6 = ips[offset + j + 6];
        long b6 = bounds(v6);
        int l6 = (int) (b6 >>> 32), h6 = (int) b6;
        int v7 = ips[offset + j + 7];
        long b7 = bounds(v7);
        int l7 = (int) (b7 >>> 32), h7 = (int) b7;
        while (l0 < h0 || l1 < h1 || l2 < h2 || l3 < h3 || l4 < h4 || l5 < h5 || l6 < h6
            || l7 < h7) {
          if (l0 < h0) {
            int m = l0 + ((h0 - l0) >>> 1);
            if (Integer.compareUnsigned(starts[m], v0) <= 0) l0 = m + 1;
            else h0 = m;
          }
          if (l1 < h1) {
            int m = l1 + ((h1 - l1) >>> 1);
            if (Integer.compareUnsigned(starts[m], v1) <= 0) l1 = m + 1;
            else h1 = m;
          }
          if (l2 < h2) {
            int m = l2 + ((h2 - l2) >>> 1);
            if (Integer.compareUnsigned(starts[m], v2) <= 0) l2 = m + 1;
            else h2 = m;
          }
          if (l3 < h3) {
            int m = l3 + ((h3 - l3) >>> 1);
            if (Integer.compareUnsigned(starts[m], v3) <= 0) l3 = m + 1;
            else h3 = m;
          }
          if (l4 < h4) {
            int m = l4 + ((h4 - l4) >>> 1);
            if (Integer.compareUnsigned(starts[m], v4) <= 0) l4 = m + 1;
            else h4 = m;
          }
          if (l5 < h5) {
            int m = l5 + ((h5 - l5) >>> 1);
            if (Integer.compareUnsigned(starts[m], v5) <= 0) l5 = m + 1;
            else h5 = m;
          }
          if (l6 < h6) {
            int m = l6 + ((h6 - l6) >>> 1);
            if (Integer.compareUnsigned(starts[m], v6) <= 0) l6 = m + 1;
            else h6 = m;
          }
          if (l7 < h7) {
            int m = l7 + ((h7 - l7) >>> 1);
            if (Integer.compareUnsigned(starts[m], v7) <= 0) l7 = m + 1;
            else h7 = m;
          }
        }
        rows[out + j + 0] = match(l0 - 1, v0);
        rows[out + j + 1] = match(l1 - 1, v1);
        rows[out + j + 2] = match(l2 - 1, v2);
        rows[out + j + 3] = match(l3 - 1, v3);
        rows[out + j + 4] = match(l4 - 1, v4);
        rows[out + j + 5] = match(l5 - 1, v5);
        rows[out + j + 6] = match(l6 - 1, v6);
        rows[out + j + 7] = match(l7 - 1, v7);
      }
      return j;
    }
  }

  static final class Eytzinger extends SearchIndexes {
    final int[] keys, originalRows, ends;
    final int size;

    Eytzinger(int[] starts, int[] ends) {
      size = starts.length;
      this.ends = ends;
      keys = new int[size + 1];
      originalRows = new int[size + 1];
      fill(1L, 0, starts);
    }

    private int fill(long node, int next, int[] starts) {
      if (node > size) return next;
      next = fill(node * 2, next, starts);
      keys[(int) node] = starts[next];
      originalRows[(int) node] = next++;
      return fill(node * 2 + 1, next, starts);
    }

    @Override
    void lookup(int[] ips, int offset, int length, long[] rows, int out) {
      for (int j = 0; j < length; j++) {
        int ip = ips[offset + j], best = -1;
        long node = 1;
        while (node <= size) {
          int p = (int) node;
          if (Integer.compareUnsigned(keys[p], ip) <= 0) {
            best = originalRows[p];
            node = node * 2 + 1;
          } else node *= 2;
        }
        rows[out + j] = best >= 0 && Integer.compareUnsigned(ip, ends[best]) <= 0 ? best : -1;
      }
    }

    @Override
    long bytes() {
      return 4L * keys.length + 4L * originalRows.length;
    }
  }
}
