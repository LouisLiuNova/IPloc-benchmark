#!/usr/bin/env python3
"""独立 PyArrow 流式抽样：按每批终点查找首个 >= 查询值的行，再检查起点。"""
import argparse, base64, hashlib, json, random
from pathlib import Path
import numpy as np
import pyarrow.parquet as pq
FIELDS=['country','province','scene','isp','owner','areacode','user','asnumber']
def generate(dataset, output):
    path=Path(dataset); out=Path(output);out.mkdir(parents=True,exist_ok=True)
    digest=hashlib.file_digest(path.open('rb'),'sha256').hexdigest()
    p=pq.ParquetFile(path); rng=random.Random(20261104)
    queries={0,1,2**31-1,2**31,2**32-2,2**32-1}
    queries.update(rng.randrange(2**32) for _ in range(4096))
    # 独立从元数据选择每个 Row Group 的起终点，覆盖组间边界。
    for g in range(p.metadata.num_row_groups):
        for c in (0,1):
            stats=p.metadata.row_group(g).column(c).statistics
            if stats and stats.has_min_max: queries.update([stats.min,stats.max])
    queries=np.array(sorted(queries),dtype=np.int64)
    expected={}; base=0; row_count=0; utf8={f:0 for f in FIELDS}; nulls={f:0 for f in FIELDS}; previous=None
    for batch in p.iter_batches(batch_size=65536,columns=['start_ip_int','end_ip_int']+FIELDS,use_threads=False):
        a=batch.column(0);z=batch.column(1)
        if a.null_count or z.null_count: raise ValueError('null boundary')
        starts=a.to_numpy();ends=z.to_numpy()
        if np.any(starts<0) or np.any(ends>2**32-1) or np.any(starts>ends) or np.any(starts[1:]<=ends[:-1]) or (previous is not None and starts[0]<=previous): raise ValueError('invalid ranges')
        previous=int(ends[-1]);row_count+=len(starts)
        at=np.searchsorted(ends,queries,side='left');safe=np.minimum(at,len(starts)-1)
        hit=(at<len(starts)) & (queries>=starts[safe])
        for qi in np.flatnonzero(hit):
            r=int(at[qi]);values=[batch.column(f+2)[r].as_py() for f in range(8)]
            q=int(queries[qi])
            if q in expected: raise ValueError('multiple hits')
            expected[q]=(base+r,values)
        # 仅投影列容量；不物化全量字符串。
        import pyarrow.compute as pc
        for f in FIELDS:
            c=batch.column(batch.schema.get_field_index(f));nulls[f]+=c.null_count
            utf8[f]+=pc.sum(pc.binary_length(c)).as_py() or 0
        base+=len(starts)
    with (out/'oracle.tsv').open('w') as f:
        for q in queries:
            row,values=expected.get(int(q),(-1,[None]*8))
            encoded=['-' if v is None else base64.b64encode(v.encode()).decode() for v in values]
            f.write('\t'.join([str(q),str(row)]+encoded)+'\n')
    info={'sha256':digest,'rows':row_count,'rowGroups':p.metadata.num_row_groups,'columns':p.metadata.num_columns,'sampleCount':len(queries),'payloadUtf8Bytes':utf8,'payloadNulls':nulls}
    (out/'dataset.json').write_text(json.dumps(info,indent=2));print(json.dumps(info))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('dataset');p.add_argument('output');a=p.parse_args();generate(a.dataset,a.output)
