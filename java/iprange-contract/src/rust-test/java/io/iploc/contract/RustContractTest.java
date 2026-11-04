package io.iploc.contract;

import io.iploc.api.IpRangeSnapshot;
import io.iploc.rust.RustIpRangeSnapshot;
import java.nio.file.Path;

class RustContractTest extends SnapshotContract {
  @Override IpRangeSnapshot open(Path p) throws Exception { return RustIpRangeSnapshot.open(p); }
}
class RustPrefix16ContractTest extends SnapshotContract {
  @Override IpRangeSnapshot open(Path p) throws Exception { return RustIpRangeSnapshot.open(p, "prefix16"); }
}
class RustPrefix20ContractTest extends SnapshotContract {
  @Override IpRangeSnapshot open(Path p) throws Exception { return RustIpRangeSnapshot.open(p, "prefix20"); }
}
