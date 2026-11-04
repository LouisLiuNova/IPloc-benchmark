package io.iploc.java;

/** Java 搜索策略；不改变快照行号、载荷布局及公共查询契约。 */
public enum SearchAlgorithm {
  BINARY("binary", 0, 1, false),
  PREFIX_16("prefix16", 16, 1, false),
  PREFIX_20("prefix20", 20, 1, false),
  PREFIX_TWO_LEVEL("prefix16-24", 16, 1, true),
  INTERLEAVED_4("interleaved4", 0, 4, false),
  INTERLEAVED_8("interleaved8", 0, 8, false),
  PREFIX_16_INTERLEAVED_4("prefix16-interleaved4", 16, 4, false),
  PREFIX_16_INTERLEAVED_8("prefix16-interleaved8", 16, 8, false),
  EYTZINGER("eytzinger", 0, 1, false);

  private final String id;
  final int prefixBits, lanes;
  final boolean adaptive;

  SearchAlgorithm(String id, int prefixBits, int lanes, boolean adaptive) {
    this.id = id;
    this.prefixBits = prefixBits;
    this.lanes = lanes;
    this.adaptive = adaptive;
  }

  public String id() {
    return id;
  }

  public static SearchAlgorithm fromId(String id) {
    for (SearchAlgorithm algorithm : values()) if (algorithm.id.equals(id)) return algorithm;
    throw new IllegalArgumentException("Unknown Java search algorithm: " + id);
  }
}
