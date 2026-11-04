package io.iploc.api;

public final class StringLookup {
  public enum Status {
    INVALID_INPUT,
    MATCHED,
    NOT_FOUND
  }

  public final Status[] statuses;
  public final long[] rowIds;
  public final PayloadBatch payload;

  public StringLookup(Status[] statuses, long[] rowIds, PayloadBatch payload) {
    this.statuses = statuses;
    this.rowIds = rowIds;
    this.payload = payload;
  }
}
