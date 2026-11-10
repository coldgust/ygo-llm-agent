package com.github.coldgust.ygoprosdk.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：{@code MSG_SELECT_CHAIN} 的分帧长度。
 */
class MsgFramingChainTest {

    /** 真实帧（STOC_GAME_MSG payload，含 type）：player=0，1 条选项 = 闪刀姬-零衣(26077387)。 */
    private static final byte[] RAYE_CHAIN =
            HexFormat.of().parseHex("1000010040000000000000000000cbe88d0100040001b08cde18");

    /** 上面那一帧的 14 字节选项部分（descType+forced+code+location+description）。 */
    private static final byte[] RAYE_OPTION =
            HexFormat.of().parseHex("0000cbe88d0100040001b08cde18");

    @Test
    void realFrameIsWellFormed() {
        assertEquals(26, RAYE_CHAIN.length, "真实帧 = type(1) + 头(11) + 选项(14)");
        assertEquals(14, RAYE_OPTION.length);
    }

    @Test
    void chainSizeUsesCountFieldNotPlayer() {
        assertEquals(26, MsgFraming.totalSize(16, RAYE_CHAIN, 0), "n=1 的连锁帧应算 26 字节");
    }

    @Test
    void splitKeepsTheWholeChainMessage() {
        List<MsgFraming.Msg> msgs = MsgFraming.split(RAYE_CHAIN);
        assertEquals(1, msgs.size(), "整帧就是一条消息");
        assertEquals(16, msgs.getFirst().type());
        assertEquals(25, msgs.getFirst().size() - 1, "body 应含完整 25 字节（11 头 + 14 选项）");
    }

    @Test
    void twoOptionsAreCountedFully() throws Exception {
        // player=1, count=2 → 12 + 28 = 40；旧实现按 player 读成 1 条，只切 26
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0x10, 0x01, 0x02, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        out.write(RAYE_OPTION);
        out.write(RAYE_OPTION);
        byte[] frame = out.toByteArray();

        assertEquals(40, frame.length);
        assertEquals(40, MsgFraming.totalSize(16, frame, 0));
        assertTrue(MsgFraming.plausible(16, frame, 0, 40), "切出来的长度应通过形态校验");
    }
}
