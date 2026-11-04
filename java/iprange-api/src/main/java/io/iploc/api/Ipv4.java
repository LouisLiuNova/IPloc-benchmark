package io.iploc.api;
public final class Ipv4 {
    private Ipv4() {}
    /** 严格 ASCII 点分十进制；非法输入返回 null。 */
    public static Long parse(String s) {
        if (s == null || s.length() < 7 || s.length() > 15) return null;
        long result = 0; int at = 0;
        for (int part = 0; part < 4; part++) {
            int start = at, value = 0;
            while (at < s.length() && s.charAt(at) >= '0' && s.charAt(at) <= '9') {
                value = value * 10 + s.charAt(at++) - '0';
                if (value > 255 || at - start > 3) return null;
            }
            if (at == start || (at - start > 1 && s.charAt(start) == '0')) return null;
            result = (result << 8) | value;
            if (part < 3) { if (at >= s.length() || s.charAt(at++) != '.') return null; }
        }
        return at == s.length() ? result : null;
    }
    public static String format(int value) {
        return (value >>> 24) + "." + ((value >>> 16) & 255) + "." + ((value >>> 8) & 255) + "." + (value & 255);
    }
}
