package com.github.coldgust.ygoprosdk.core;

import com.github.coldgust.ygodomain.duel.CardInstance;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.duel.Position;
import com.github.coldgust.ygodomain.zone.ZoneKind;
import com.github.coldgust.ygoprosdk.protocol.ByteReader;
import com.github.coldgust.ygoprosdk.protocol.ByteWriter;
import com.github.coldgust.ygoprosdk.protocol.ProtocolIds;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 战场镜像的计数正确性（回归：盖 3 张被数成 6 张）。
 */
class StateProjectorTest {

    private static final long SKY_STRIKER_MOB = 9726840L;
    private static final long EFFECT_VEILER = 24299458L;
    private static final long ASH_BLOSSOM = 98338152L;

    private static ByteReader reader(ByteWriter writer) {
        return new ByteReader(writer.toByteArray());
    }

    private static StateProjector startDuel(int selfSeat) {
        StateProjector projector = new StateProjector(null);
        projector.apply(ProtocolIds.MSG_START, reader(new ByteWriter()
                .u8(selfSeat)   // playertype = 接收者座位
                .u8(5)          // master rule 5
                .i32(8000).i32(8000)
                .i16(40).i16(15).i16(40).i16(15)));
        return projector;
    }

    /** 从卡组发一张到指定手牌位（真实卡号）。 */
    private static void dealToHand(StateProjector projector, int seat, long code, int handSeq) {
        projector.apply(ProtocolIds.MSG_MOVE, reader(new ByteWriter()
                .u32(code)
                .u32(ZoneMapper.pack(seat, ZoneMapper.LOC_DECK, 0, 0))
                .u32(ZoneMapper.pack(seat, ZoneMapper.LOC_HAND, handSeq, 0))
                .u32(0)));
    }

    /** 从手牌盖放到魔陷区：MSG_MOVE（真值）+ MSG_SET（抹零），顺序与实机一致。 */
    private static void setFromHand(StateProjector projector, int seat, long code,
                                    int handSeq, int zoneSeq) {
        projector.apply(ProtocolIds.MSG_MOVE, reader(new ByteWriter()
                .u32(code)
                .u32(ZoneMapper.pack(seat, ZoneMapper.LOC_HAND, handSeq, 0))
                .u32(ZoneMapper.pack(seat, ZoneMapper.LOC_SZONE, zoneSeq, 0))
                .u32(0)));
        projector.apply(ProtocolIds.MSG_SET, reader(new ByteWriter()
                .u32(0) // 服务端无条件抹零
                .u32(ZoneMapper.pack(seat, ZoneMapper.LOC_SZONE, zoneSeq,
                        ZoneMapper.POS_FACEDOWN_DEFENSE))));
    }

    @Test
    void settingThreeCardsCountsThreeNotSix() {
        StateProjector projector = startDuel(0);
        dealToHand(projector, 0, SKY_STRIKER_MOB, 0);
        dealToHand(projector, 0, EFFECT_VEILER, 1);
        dealToHand(projector, 0, ASH_BLOSSOM, 2);

        setFromHand(projector, 0, SKY_STRIKER_MOB, 0, 0);
        setFromHand(projector, 0, EFFECT_VEILER, 1, 1);
        setFromHand(projector, 0, ASH_BLOSSOM, 2, 2);

        DuelState state = projector.snapshot();
        assertEquals(3, state.spellTraps(PlayerSide.SELF).size(), "3 张盖卡就该数出 3 张");
        assertEquals(0, state.self().handCount(), "盖出去的牌必须从手牌里移除");
        assertEquals(3, onFieldInstances(state), "快照里不应残留同格的孤儿实例");
    }

    @Test
    void selfSetCardKeepsItsIdentity() {
        StateProjector projector = startDuel(0);
        dealToHand(projector, 0, SKY_STRIKER_MOB, 0);
        setFromHand(projector, 0, SKY_STRIKER_MOB, 0, 0);

        List<CardInstance> backrow = projector.snapshot().spellTraps(PlayerSide.SELF);
        assertEquals(1, backrow.size());
        assertFalse(backrow.getFirst().isUnknown(), "我方盖下的牌自己应该知道是什么");
        assertEquals(SKY_STRIKER_MOB, backrow.getFirst().passcode());
        assertEquals(Position.FACE_DOWN_DEFENSE, backrow.getFirst().position(),
                "MSG_SET 语义就是里侧，不能因为位置字为 0 就把朝向丢掉");
    }

    @Test
    void activatedBackrowTurnsFaceUp() {
        StateProjector projector = startDuel(0);
        dealToHand(projector, 0, SKY_STRIKER_MOB, 0);
        setFromHand(projector, 0, SKY_STRIKER_MOB, 0, 0);

        // 发动后引擎发表示形式变化：魔陷用 POS_FACEUP(0x5) 这种朝向组合位
        projector.apply(ProtocolIds.MSG_POS_CHANGE, reader(new ByteWriter()
                .u32(SKY_STRIKER_MOB)
                .u8(0)                                  // controller
                .u8(ZoneMapper.LOC_SZONE)
                .u8(0)                                  // sequence
                .u8(ZoneMapper.POS_FACEDOWN_DEFENSE)    // prevPos
                .u8(ZoneMapper.POS_FACEUP_ATTACK | ZoneMapper.POS_FACEUP_DEFENSE)));

        CardInstance card = projector.snapshot().spellTraps(PlayerSide.SELF).getFirst();
        assertTrue(card.position().isFaceUp(), "表侧魔陷不能被当成盖卡（朝向未知）");
    }

    @Test
    void opponentSetCardStaysUnknown() {
        StateProjector projector = startDuel(1); // 观察者为座位 1，座位 0 是对手
        dealToHand(projector, 0, 0, 0);          // 对手视角：MSG_MOVE 的 code 被抹零
        setFromHand(projector, 0, 0, 0, 0);

        List<CardInstance> backrow = projector.snapshot().spellTraps(PlayerSide.OPPONENT);
        assertEquals(1, backrow.size());
        assertTrue(backrow.getFirst().isUnknown(), "对方盖卡内容不可见");
    }

    /** 单字段查询块（flags 只带 CODE）：[块长 12][flags=0x1][code]。 */
    private static ByteWriter codeBlock(long code) {
        return new ByteWriter().i32(12).i32(0x000001).u32(code);
    }

    @Test
    void updateCardOnHandBackfillsIdentityInsteadOfCreatingPhantom() {
        StateProjector projector = startDuel(0);
        dealToHand(projector, 0, SKY_STRIKER_MOB, 0);

        // 引擎在检索/展示后对 手牌/墓地 等列表区域发 MSG_UPDATE_CARD：
        // 这些区域不在 zoneIndex，旧实现会新建实例 → 同一张卡两份（记录翻倍）
        projector.apply(ProtocolIds.MSG_UPDATE_CARD, reader(new ByteWriter()
                .u8(0)                       // seat
                .u8(ZoneMapper.LOC_HAND)
                .u8(0)                       // 手牌序号
                .bytes(codeBlock(EFFECT_VEILER).toByteArray())));

        DuelState state = projector.snapshot();
        assertEquals(1, state.self().handCount(), "手牌只能有这张卡");
        assertEquals(1, state.cards().values().stream()
                        .filter(c -> c.zone() != null && c.zone().kind() == ZoneKind.HAND)
                        .count(),
                "不得产生幽灵手牌实例");
        assertEquals(EFFECT_VEILER, state.self().hand().getFirst().passcode(),
                "UPDATE_CARD 带来的真实卡号要回填到手牌实例上");
    }

    @Test
    void updateCardOnOpponentHandRevealsKnownCard() {
        StateProjector projector = startDuel(0);
        // 对手一张未知手卡（code 抹零）
        projector.apply(ProtocolIds.MSG_MOVE, reader(new ByteWriter()
                .u32(0)
                .u32(ZoneMapper.pack(1, ZoneMapper.LOC_DECK, 0, 0))
                .u32(ZoneMapper.pack(1, ZoneMapper.LOC_HAND, 0, 0))
                .u32(0)));

        // 效果展示对手手卡：UPDATE_CARD 带真实卡号 → 已知手牌
        projector.apply(ProtocolIds.MSG_UPDATE_CARD, reader(new ByteWriter()
                .u8(1)
                .u8(ZoneMapper.LOC_HAND)
                .u8(0)
                .bytes(codeBlock(ASH_BLOSSOM).toByteArray())));

        DuelState state = projector.snapshot();
        assertEquals(1, state.opponent().hand().size());
        assertEquals(ASH_BLOSSOM, state.opponent().hand().getFirst().passcode(),
                "被展示的对手手卡要变成已知");
    }

    @Test
    void updateCardAndDataOnGraveyardDoNotCreatePhantoms() {
        StateProjector projector = startDuel(0);
        dealToHand(projector, 0, SKY_STRIKER_MOB, 0);
        // 手牌 → 墓地
        projector.apply(ProtocolIds.MSG_MOVE, reader(new ByteWriter()
                .u32(SKY_STRIKER_MOB)
                .u32(ZoneMapper.pack(0, ZoneMapper.LOC_HAND, 0, 0))
                .u32(ZoneMapper.pack(0, ZoneMapper.LOC_GRAVE, 0, 0))
                .u32(0)));

        projector.apply(ProtocolIds.MSG_UPDATE_CARD, reader(new ByteWriter()
                .u8(0).u8(ZoneMapper.LOC_GRAVE).u8(0)
                .bytes(codeBlock(SKY_STRIKER_MOB).toByteArray())));
        projector.apply(ProtocolIds.MSG_UPDATE_DATA, reader(new ByteWriter()
                .u8(0).u8(ZoneMapper.LOC_GRAVE)
                .bytes(codeBlock(SKY_STRIKER_MOB).toByteArray())));

        DuelState state = projector.snapshot();
        assertEquals(1, state.self().graveyard().size(), "墓地身份列表只记一次");
        assertEquals(1, state.cards().values().stream()
                        .filter(c -> c.zone() != null && c.zone().kind() == ZoneKind.GRAVEYARD)
                        .count(),
                "不得产生幽灵墓地实例");
        assertEquals(SKY_STRIKER_MOB, state.self().graveyard().getFirst().passcode());
    }

    @Test
    void drawReducesMainDeckCount() {
        StateProjector projector = startDuel(0);
        projector.apply(ProtocolIds.MSG_DRAW, reader(new ByteWriter()
                .u8(0).u8(2).u32(0).u32(0))); // 我方抽 2（对手视角未知卡）

        assertEquals(38, projector.snapshot().self().mainDeckCount(),
                "MSG_DRAW 就是从主卡组抽走，卡组剩余数必须减少");
    }

    @Test
    void placingIntoOccupiedSlotEvictsStaleInstance() {
        StateProjector projector = startDuel(0);
        dealToHand(projector, 0, SKY_STRIKER_MOB, 0);
        setFromHand(projector, 0, SKY_STRIKER_MOB, 0, 0);

        // 槽位被重复写入（异常/重连后的重放）：旧实例必须被撤出，不能与新实例共存
        projector.apply(ProtocolIds.MSG_MOVE, reader(new ByteWriter()
                .u32(EFFECT_VEILER)
                .u32(ZoneMapper.pack(0, ZoneMapper.LOC_GRAVE, 0, 0))
                .u32(ZoneMapper.pack(0, ZoneMapper.LOC_SZONE, 0, 0))
                .u32(0)));

        DuelState state = projector.snapshot();
        assertEquals(1, state.spellTraps(PlayerSide.SELF).size());
        assertEquals(1, onFieldInstances(state));
        assertEquals(EFFECT_VEILER, state.spellTraps(PlayerSide.SELF).getFirst().passcode());
    }

    @Test
    void setOnEmptySlotStillCreatesCard() {
        StateProjector projector = startDuel(0);
        // 效果直接盖放（没有前置 MSG_MOVE）：仍要能登记出场
        projector.apply(ProtocolIds.MSG_SET, reader(new ByteWriter()
                .u32(0)
                .u32(ZoneMapper.pack(0, ZoneMapper.LOC_SZONE, 3,
                        ZoneMapper.POS_FACEDOWN_DEFENSE))));

        DuelState state = projector.snapshot();
        List<CardInstance> backrow = state.spellTraps(PlayerSide.SELF);
        assertEquals(1, backrow.size());
        assertNotNull(backrow.getFirst().zone());
        assertEquals(3, backrow.getFirst().zone().sequence());
    }

    /** 快照中位于双方怪兽区/魔陷区的实例总数（用于发现同格孤儿实例）。 */
    private static long onFieldInstances(DuelState state) {
        return state.cards().values().stream()
                .filter(c -> c.zone() != null)
                .filter(c -> c.zone().kind() == ZoneKind.SPELL_TRAP_ZONE
                        || c.zone().kind() == ZoneKind.MAIN_MONSTER_ZONE
                        || c.zone().kind() == ZoneKind.EXTRA_MONSTER_ZONE)
                .count();
    }

    /**
     * 实机 MSG_UPDATE_DATA（player=0, loc=HAND, 5 张）的原始字节。
     * <p>每块 72 字节：{@code [u32 块长=72][u32 flags=0x00681FFF][u32 卡号][u32 位置打包字]…}。
     */
    private static final String HAND_UPDATE_DATA_HEX = ""
            + "000248000000ff1f68006885dc050002000a6885dc05020001000000000000000000000000000000000000"
            + "0000000000000000000000000000000004000200000000000000000000000048000000ff1f6800cbe88d01"
            + "0002010acbe88d012100000004000000000000002000000001000000dc050000dc050000dc050000dc050000"
            + "0004000200000000000000000000000048000000ff1f6800af23de000002020aaf23de002110000003000000"
            + "0000000004000000100000000000000008070000000000000807000000040002000000000000000000000000"
            + "48000000ff1f68003f0d1a020002030a3f0d1a02020000000000000000000000000000000000000000000000"
            + "0000000000000000000000000004000200000000000000000000000048000000ff1f68003b2fd5010002040a"
            + "3b2fd50104000000000000000000000000000000000000000000000000000000000000000000000000040002"
            + "000000000000000000000000";

    @Test
    void handUpdateDataKeepsRealPasscodes() {
        StateProjector projector = startDuel(0);
        projector.apply(ProtocolIds.MSG_UPDATE_DATA, reader(new ByteWriter()
                .bytes(HexFormat.of().parseHex(HAND_UPDATE_DATA_HEX))));

        DuelState state = projector.snapshot();
        assertEquals(5, state.self().handCount());
        assertEquals(List.of(98338152L, 26077387L, 14558127L, 35261759L, 30748475L),
                state.self().hand().stream().map(CardInstance::passcode).toList(),
                "手牌卡号必须是真卡号，不能是位置打包字");
        assertTrue(state.self().hand().stream().noneMatch(CardInstance::isUnknown));
        assertTrue(state.self().hand().stream()
                        .allMatch(c -> c.zone() != null && c.zone().kind() == ZoneKind.HAND),
                "手牌实例必须落在手牌区（而不是被当成场上卡）");
    }
}
