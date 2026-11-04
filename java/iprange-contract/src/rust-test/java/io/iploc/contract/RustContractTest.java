package io.iploc.contract;

import io.iploc.api.IpRangeSnapshot;
import io.iploc.rust.RustIpRangeSnapshot;
import java.nio.file.Path;

class RustContractTest extends SnapshotContract {
  @Override
  IpRangeSnapshot open(Path p) throws Exception {
    return RustIpRangeSnapshot.open(p);
  }
}

class RustPrefix16ContractTest extends SnapshotContract {
  @Override
  IpRangeSnapshot open(Path p) throws Exception {
    return RustIpRangeSnapshot.open(p, "prefix16");
  }
}

class RustPrefix20ContractTest extends SnapshotContract {
  @Override
  IpRangeSnapshot open(Path p) throws Exception {
    return RustIpRangeSnapshot.open(p, "prefix20");
  }
}

class RustTwoLevelContractTest extends SnapshotContract {
  @Override
  IpRangeSnapshot open(Path p) throws Exception {
    return RustIpRangeSnapshot.open(p, "prefix16-24");
  }
}

class RustInterleaved4ContractTest extends SnapshotContract {
  @Override
  IpRangeSnapshot open(Path p) throws Exception {
    return RustIpRangeSnapshot.open(p, "interleaved4");
  }
}

class RustInterleaved8ContractTest extends SnapshotContract {
  @Override
  IpRangeSnapshot open(Path p) throws Exception {
    return RustIpRangeSnapshot.open(p, "interleaved8");
  }
}

class RustPrefixInterleaved4ContractTest extends SnapshotContract {
  @Override
  IpRangeSnapshot open(Path p) throws Exception {
    return RustIpRangeSnapshot.open(p, "prefix16-interleaved4");
  }
}

class RustPrefixInterleaved8ContractTest extends SnapshotContract {
  @Override
  IpRangeSnapshot open(Path p) throws Exception {
    return RustIpRangeSnapshot.open(p, "prefix16-interleaved8");
  }
}

class RustEytzingerContractTest extends SnapshotContract {
  @Override
  IpRangeSnapshot open(Path p) throws Exception {
    return RustIpRangeSnapshot.open(p, "eytzinger");
  }
}
