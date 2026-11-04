package io.iploc.api;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** 数组归此结果所有；调用方可以消费和修改，不影响快照。 */
public final class PayloadBatch {
    public static final List<String> FIELDS = List.of("country", "province", "scene", "isp", "owner", "areacode", "user", "asnumber");
    public final String datasetVersion;
    public final int size;
    public final BitSet matched;
    public final Column[] columns;
    public PayloadBatch(String version, int size, BitSet matched, Column[] columns) {
        this.datasetVersion = version; this.size = size; this.matched = matched; this.columns = columns;
    }
    public static final class Column {
        public final byte[] data;
        public final int[] offsets;
        public final BitSet valid;
        public Column(byte[] data, int[] offsets, BitSet valid) { this.data=data; this.offsets=offsets; this.valid=valid; }
        public String stringAt(int row) {
            if (row < 0 || row >= offsets.length - 1) throw new IndexOutOfBoundsException();
            return valid.get(row) ? new String(data, offsets[row], offsets[row+1]-offsets[row], StandardCharsets.UTF_8) : null;
        }
    }
    public String[][] toStrings() {
        String[][] result = new String[size][columns.length];
        for (int r=0;r<size;r++) for(int c=0;c<columns.length;c++) result[r][c]=columns[c].stringAt(r);
        return result;
    }
    /** client/server 共用同一字段映射，未命中和 null 字段输出 null。 */
    public Map<String,String> businessRow(int row, boolean client) {
        if (row < 0 || row >= size) throw new IndexOutOfBoundsException();
        Map<String,String> result = new LinkedHashMap<>();
        for (int c=0;c<columns.length;c++) result.put("IPLOC_"+(client?"CLIENT_":"SERVER_")+FIELDS.get(c).toUpperCase(Locale.ROOT), columns[c].stringAt(row));
        return result;
    }
}
