package com.github.coldgust.ygoprosdk.protocol;

import java.util.ArrayList;
import java.util.List;
import java.io.ByteArrayOutputStream;

/**
 * 帧编解码：{@code [u16 length][u8 type][payload]}，<b>length 包含 type 字节</b>。
 * 依据规范 §2.2。
 */
public final class FrameCodec {
    private FrameCodec() {
    }

    public static byte[] encode(int type, byte[] payload) {
        int len = 1 + (payload == null ? 0 : payload.length);
        if (len > 0xFFFF) {
            throw new IllegalArgumentException("帧长超限: " + len);
        }
        byte[] out = new byte[2 + len];
        out[0] = (byte) (len & 0xFF);
        out[1] = (byte) ((len >>> 8) & 0xFF);
        out[2] = (byte) type;
        if (payload != null && payload.length > 0) {
            System.arraycopy(payload, 0, out, 3, payload.length);
        }
        return out;
    }

    public record Frame(int type, byte[] payload) {
    }

    /**
     * 从累积缓冲中切出全部完整帧；未凑齐的尾部字节保留在 buffer 中。
     */
    public static List<Frame> split(ByteArrayOutputStream buffer) {
        List<Frame> frames = new ArrayList<>();
        byte[] data = buffer.toByteArray();
        int off = 0;
        while (off + 3 <= data.length) {
            int len = (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8);
            if (len < 1) {
                off += 1;
                continue;
            }
            if (off + 2 + len > data.length) {
                break;
            }
            int type = data[off + 2] & 0xFF;
            byte[] payload = new byte[len - 1];
            System.arraycopy(data, off + 3, payload, 0, payload.length);
            frames.add(new Frame(type, payload));
            off += 2 + len;
        }
        if (off > 0) {
            byte[] rest = new byte[data.length - off];
            System.arraycopy(data, off, rest, 0, rest.length);
            buffer.reset();
            buffer.write(rest, 0, rest.length);
        }
        return frames;
    }
}
