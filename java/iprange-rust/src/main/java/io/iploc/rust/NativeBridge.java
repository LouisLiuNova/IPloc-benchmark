package io.iploc.rust;

import io.iploc.api.PayloadBatch;
import java.io.IOException;
import java.nio.file.Path;

final class NativeBridge {
  static {
    String configured = System.getProperty("iploc.native.path");
    if (configured == null || !Path.of(configured).isAbsolute())
      throw new UnsatisfiedLinkError("iploc.native.path must specify an absolute native library path");
    System.load(configured);
    if (abiVersion() != 1) throw new UnsatisfiedLinkError("Rust JNI ABI version mismatch");
  }
  private NativeBridge() {}
  static native int abiVersion();
  static native long open(String path, String algorithm) throws IOException;
  static native long synthetic(int count, String algorithm);
  static native long view(long handle, String algorithm);
  static native void close(long handle);
  static native String version(long handle);
  static native long rowCount(long handle);
  static native long indexBytes(long handle);
  static native void lookup(long handle, int[] ips, int offset, int length, long[] rows, int out);
  static native PayloadBatch fetch(long handle, long[] rows, int offset, int length);
}
