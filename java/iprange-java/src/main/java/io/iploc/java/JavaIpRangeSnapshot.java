package io.iploc.java;

import io.iploc.api.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.*;
import org.apache.parquet.io.api.*;
import org.apache.parquet.schema.*;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;

/** 连续无符号边界与分块 UTF-8 载荷，默认基础二分。 */
public final class JavaIpRangeSnapshot implements IpRangeSnapshot {
  private static final int CHUNK = 65_536;
  private final AtomicReference<State> state;

  private JavaIpRangeSnapshot(State s) {
    state = new AtomicReference<>(s);
  }

  private State acquire() {
    State s = state.get();
    if (s == null) throw new IllegalStateException("Snapshot closed");
    return s;
  }

  public static JavaIpRangeSnapshot open(Path file) throws IOException {
    return open(file, false);
  }

  public static JavaIpRangeSnapshot open(Path file, boolean prefix) throws IOException {
    return open(file, prefix ? SearchAlgorithm.PREFIX_16 : SearchAlgorithm.BINARY);
  }

  public static JavaIpRangeSnapshot open(Path file, SearchAlgorithm algorithm) throws IOException {
    Objects.requireNonNull(algorithm, "algorithm");
    BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class);
    String version = sha256(file);
    try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(file))) {
      MessageType source = reader.getFooter().getFileMetaData().getSchema();
      List<Type> fields = new ArrayList<>();
      List<String> names = new ArrayList<>(List.of("start_ip_int", "end_ip_int"));
      names.addAll(PayloadBatch.FIELDS);
      for (int i = 0; i < names.size(); i++) {
        String name = names.get(i);
        if (!source.containsField(name)) throw new IOException("Missing field: " + name);
        Type t = source.getType(name);
        if (!t.isPrimitive() || t.isRepetition(Type.Repetition.REPEATED))
          throw new IOException("Unsupported field: " + name);
        PrimitiveType p = t.asPrimitiveType();
        if (p.getPrimitiveTypeName() != (i < 2 ? PrimitiveTypeName.INT64 : PrimitiveTypeName.BINARY)
            || (i < 2
                ? p.getLogicalTypeAnnotation() != null
                : !LogicalTypeAnnotation.stringType().equals(p.getLogicalTypeAnnotation())))
          throw new IOException("Invalid type: " + name + " " + t);
        fields.add(t);
      }
      MessageType projection = new MessageType("iploc", fields);
      reader.setRequestedSchema(projection);
      long count = reader.getRecordCount();
      if (count > Integer.MAX_VALUE - 8)
        throw new IOException("Dataset exceeds contiguous array capacity: " + count);
      Builder b = new Builder((int) count);
      MessageColumnIO io = new ColumnIOFactory().getColumnIO(projection, source, true);
      int rg = 0;
      PageReadStore pages;
      while ((pages = reader.readNextRowGroup()) != null) {
        try (PageReadStore owned = pages) {
          Materializer materializer = new Materializer(b);
          RecordReader<Void> records = io.getRecordReader(owned, materializer);
          for (long r = 0; r < owned.getRowCount(); r++) {
            try {
              records.read();
            } catch (RuntimeException ex) {
              throw new IOException(
                  file + " rowGroup=" + rg + " row=" + b.at + ": " + ex.getMessage(), ex);
            }
          }
        }
        rg++;
      }
      State s = b.finish(version, algorithm);
      BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class);
      if (before.size() != after.size()
          || !before.lastModifiedTime().equals(after.lastModifiedTime())
          || !version.equals(sha256(file)))
        throw new IOException("Dataset changed while loading: " + file);
      return new JavaIpRangeSnapshot(s);
    }
  }

  public static String sha256(Path path) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = Files.newInputStream(path)) {
        byte[] buf = new byte[8 * 1024 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
      }
      StringBuilder hex = new StringBuilder();
      for (byte v : digest.digest()) hex.append(String.format("%02x", v & 255));
      return hex.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new AssertionError(ex);
    }
  }
  /** 评估专用：固定 stride=256、width=128，可由独立算术推导命中行。 */
  public static JavaIpRangeSnapshot synthetic(int count, boolean prefix) {
    return synthetic(count, prefix ? SearchAlgorithm.PREFIX_16 : SearchAlgorithm.BINARY);
  }

  public static JavaIpRangeSnapshot synthetic(int count, SearchAlgorithm algorithm) {
    Objects.requireNonNull(algorithm, "algorithm");
    if (count < 0 || count > 16_777_216) throw new IllegalArgumentException("Synthetic row count");
    Builder b = new Builder(count);
    byte[][] value = new byte[8][];
    for (int f = 0; f < 8; f++)
      value[f] =
          ("synthetic-" + PayloadBatch.FIELDS.get(f))
              .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    for (int i = 0; i < count; i++) b.append((long) i * 256, (long) i * 256 + 127, value);
    return new JavaIpRangeSnapshot(b.finish("synthetic-v1-" + count, algorithm));
  }

  @Override
  public String datasetVersion() {
    return acquire().version;
  }

  @Override
  public long rowCount() {
    return acquire().starts.length;
  }

  @Override
  public void close() {
    state.set(null);
  }

  @Override
  public void lookupBatch(int[] ips, int offset, int length, long[] rows, int out) {
    State s = acquire();
    BatchChecks.range(ips.length, offset, length);
    BatchChecks.range(rows.length, out, length);
    s.index.lookup(ips, offset, length, rows, out);
  }

  /** 共享同一不可变数据，独立管理视图生命周期；只额外构建所选搜索索引。 */
  public JavaIpRangeSnapshot withAlgorithm(SearchAlgorithm algorithm) {
    State s = acquire();
    return new JavaIpRangeSnapshot(
        new State(
            s.version, s.starts, s.ends, s.chunks, Objects.requireNonNull(algorithm, "algorithm")));
  }

  public SearchAlgorithm algorithm() {
    return acquire().algorithm;
  }
  /** 额外索引基本类型数组字节数，不是进程 RSS。 */
  public long indexBytes() {
    return acquire().index.bytes();
  }

  @Override
  public PayloadBatch fetchBatch(long[] rows, int offset, int length) {
    State s = acquire();
    BatchChecks.range(rows.length, offset, length);
    BitSet matched = new BitSet(length);
    for (int i = 0; i < length; i++) {
      long r = rows[offset + i];
      if (r < -1 || r >= s.starts.length)
        throw new IllegalArgumentException("Invalid rowId at " + i + ": " + r);
      if (r >= 0) matched.set(i);
    }
    PayloadBatch.Column[] columns = new PayloadBatch.Column[8];
    for (int f = 0; f < 8; f++) {
      int[] offsets = new int[length + 1];
      BitSet valid = new BitSet(length);
      long bytes = 0;
      for (int i = 0; i < length; i++) {
        if (matched.get(i)) {
          int r = (int) rows[offset + i];
          PayloadBatch.Column c = s.chunks[r / CHUNK][f];
          int local = r % CHUNK;
          if (c.valid.get(local)) {
            valid.set(i);
            bytes += c.offsets[local + 1] - c.offsets[local];
          }
        }
        if (bytes > Integer.MAX_VALUE - 8)
          throw new IllegalArgumentException("Payload column exceeds array capacity");
        offsets[i + 1] = (int) bytes;
      }
      byte[] data = new byte[(int) bytes];
      for (int i = valid.nextSetBit(0); i >= 0; i = valid.nextSetBit(i + 1)) {
        int r = (int) rows[offset + i];
        PayloadBatch.Column c = s.chunks[r / CHUNK][f];
        int local = r % CHUNK;
        System.arraycopy(c.data, c.offsets[local], data, offsets[i], offsets[i + 1] - offsets[i]);
      }
      columns[f] = new PayloadBatch.Column(data, offsets, valid);
    }
    return new PayloadBatch(s.version, length, matched, columns);
  }

  private static final class State {
    final String version;
    final int[] starts, ends;
    final PayloadBatch.Column[][] chunks;
    final SearchAlgorithm algorithm;
    final SearchIndexes index;

    State(String v, int[] a, int[] z, PayloadBatch.Column[][] c, SearchAlgorithm algorithm) {
      version = v;
      starts = a;
      ends = z;
      chunks = c;
      this.algorithm = algorithm;
      index = SearchIndexes.create(a, z, algorithm);
    }
  }

  private static final class Builder {
    final int[] starts, ends;
    int at = 0, local = 0;
    final List<PayloadBatch.Column[]> chunks = new ArrayList<>();
    ByteArrayOutputStream[] data;
    int[][] offsets;
    BitSet[] valid;

    Builder(int count) {
      starts = new int[count];
      ends = new int[count];
      reset();
    }

    void reset() {
      data = new ByteArrayOutputStream[8];
      offsets = new int[8][CHUNK + 1];
      valid = new BitSet[8];
      for (int f = 0; f < 8; f++) {
        data[f] = new ByteArrayOutputStream();
        valid[f] = new BitSet(CHUNK);
      }
      local = 0;
    }

    void flush() {
      if (local == 0) return;
      PayloadBatch.Column[] c = new PayloadBatch.Column[8];
      for (int f = 0; f < 8; f++)
        c[f] =
            new PayloadBatch.Column(
                data[f].toByteArray(), Arrays.copyOf(offsets[f], local + 1), valid[f]);
      chunks.add(c);
      reset();
    }

    void append(long start, long end, byte[][] values) {
      if (at >= starts.length) throw new IllegalArgumentException("Row count exceeds metadata");
      if (start < 0 || end > 0xffff_ffffL || end < start)
        throw new IllegalArgumentException("Invalid IPv4 boundaries");
      if (at > 0
          && (start <= Integer.toUnsignedLong(starts[at - 1])
              || start <= Integer.toUnsignedLong(ends[at - 1])))
        throw new IllegalArgumentException("Unsorted or overlapping range");
      starts[at] = (int) start;
      ends[at] = (int) end;
      for (int f = 0; f < 8; f++) {
        byte[] value = values[f];
        if (value != null) {
          if (value.length > Integer.MAX_VALUE - 8 - data[f].size())
            throw new IllegalArgumentException("Chunk column capacity");
          data[f].write(value, 0, value.length);
          valid[f].set(local);
        }
        offsets[f][local + 1] = data[f].size();
      }
      at++;
      local++;
      if (local == CHUNK) flush();
    }

    State finish(String version, SearchAlgorithm algorithm) {
      if (at != starts.length) throw new IllegalArgumentException("Row count mismatch");
      flush();
      return new State(
          version, starts, ends, chunks.toArray(new PayloadBatch.Column[0][]), algorithm);
    }
  }

  private static final class Materializer extends RecordMaterializer<Void> {
    final Converter[] converters = new Converter[10];
    final byte[][] values = new byte[8][];
    long start, end;
    boolean hasStart, hasEnd;
    final GroupConverter root;

    Materializer(Builder b) {
      converters[0] =
          new PrimitiveConverter() {
            @Override
            public void addLong(long v) {
              start = v;
              hasStart = true;
            }
          };
      converters[1] =
          new PrimitiveConverter() {
            @Override
            public void addLong(long v) {
              end = v;
              hasEnd = true;
            }
          };
      for (int f = 0; f < 8; f++) {
        final int index = f;
        converters[f + 2] =
            new PrimitiveConverter() {
              @Override
              public void addBinary(Binary v) {
                values[index] = v.getBytes();
              }
            };
      }
      root =
          new GroupConverter() {
            @Override
            public Converter getConverter(int field) {
              return converters[field];
            }

            @Override
            public void start() {
              hasStart = false;
              hasEnd = false;
              Arrays.fill(values, null);
            }

            @Override
            public void end() {
              if (!hasStart || !hasEnd) throw new IllegalArgumentException("Null boundary");
              b.append(start, end, values);
            }
          };
    }

    @Override
    public Void getCurrentRecord() {
      return null;
    }

    @Override
    public GroupConverter getRootConverter() {
      return root;
    }
  }
}
