package com.github.coldgust.ygoprosdk.protocol;

import java.nio.charset.StandardCharsets;

/**
 * 小端字节读取游标（协议全部字段为 LE）。
 * 有符号 / 无符号按读取方法区分；越界抛 {@link IndexOutOfBoundsException}。
 */
public final class ByteReader {
    private final byte[] data;
    private int pos;

    public ByteReader(byte[] data) {
        this(data, 0);
    }

    public ByteReader(byte[] data, int pos) {
        this.data = data;
        this.pos = pos;
    }

    public int position() {
        return pos;
    }

    public int remaining() {
        return data.length - pos;
    }

    public boolean hasRemaining() {
        return pos < data.length;
    }

    /** 剩余字节是否足够再读 n 个（容错解码用）。 */
    public boolean canRead(int n) {
        return remaining() >= n;
    }

    public int peek() {
        return data[pos] & 0xFF;
    }

    public int u8() {
        return data[pos++] & 0xFF;
    }

    public int u16() {
        int v = (data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8);
        pos += 2;
        return v;
    }

    public int i16() {
        return (short) u16();
    }

    public long u32() {
        long v = (data[pos] & 0xFFL)
                | ((data[pos + 1] & 0xFFL) << 8)
                | ((data[pos + 2] & 0xFFL) << 16)
                | ((data[pos + 3] & 0xFFL) << 24);
        pos += 4;
        return v & 0xFFFFFFFFL;
    }

    public int i32() {
        return (int) u32();
    }

    public byte[] bytes(int n) {
        byte[] out = new byte[n];
        System.arraycopy(data, pos, out, 0, n);
        pos += n;
        return out;
    }

    /** 定长 UTF-16LE 字符串（如玩家名 40 字节），取到首个 \0。 */
    public String utf16Fixed(int byteLength) {
        String s = new String(bytes(byteLength), StandardCharsets.UTF_16LE);
        int zero = s.indexOf('\0');
        return zero >= 0 ? s.substring(0, zero) : s;
    }

    public void skip(int n) {
        pos += n;
    }
}
