package com.github.coldgust.ygoprosdk.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 小端字节写出器（用于构造 CTOS payload）。
 */
public final class ByteWriter {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream(64);

    public ByteWriter u8(int v) {
        out.write(v & 0xFF);
        return this;
    }

    public ByteWriter u16(int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        return this;
    }

    public ByteWriter i16(int v) {
        return u16(v);
    }

    public ByteWriter u32(long v) {
        out.write((int) (v & 0xFF));
        out.write((int) ((v >>> 8) & 0xFF));
        out.write((int) ((v >>> 16) & 0xFF));
        out.write((int) ((v >>> 24) & 0xFF));
        return this;
    }

    public ByteWriter i32(int v) {
        return u32(v);
    }

    public ByteWriter bytes(byte[] b) {
        out.writeBytes(b);
        return this;
    }

    public ByteWriter zeros(int n) {
        out.write(new byte[n], 0, n);
        return this;
    }

    /** 定长 UTF-16LE 名字（40 字节，\0 结尾补零）。 */
    public ByteWriter utf16Fixed(String s, int byteLength) {
        byte[] raw = (s == null ? "" : s).getBytes(StandardCharsets.UTF_16LE);
        byte[] buf = new byte[byteLength];
        System.arraycopy(raw, 0, buf, 0, Math.min(raw.length, byteLength));
        return bytes(buf);
    }

    public byte[] toByteArray() {
        return out.toByteArray();
    }
}
