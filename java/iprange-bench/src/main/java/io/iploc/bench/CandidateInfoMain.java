package io.iploc.bench;

/** 不加载数据即可取得 Native 编译时信息，供 manifest 锁定制品来源。 */
public final class CandidateInfoMain {
  public static void main(String[] args) throws Exception {
    System.out.println(
        CandidateSnapshot.invoke(
            Class.forName("io.iploc.rust.RustIpRangeSnapshot"),
            "nativeBuildInfo",
            new Class<?>[0]));
  }
}
