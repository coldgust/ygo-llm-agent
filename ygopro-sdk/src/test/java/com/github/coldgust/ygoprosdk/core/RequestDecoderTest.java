package com.github.coldgust.ygoprosdk.core;

import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.zone.ZoneKind;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoprosdk.protocol.ByteReader;
import com.github.coldgust.ygoprosdk.protocol.ByteWriter;
import com.github.coldgust.ygoprosdk.protocol.ProtocolIds;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestDecoderTest {

    private static final RequestDecoder.MirrorView MIRROR = new RequestDecoder.MirrorView() {
        @Override
        public int selfSeat() {
            return 0;
        }

        @Override
        public ChoiceRequest.CardChoice choice(int absController, int location, int sequence, long code) {
            return new ChoiceRequest.CardChoice(0, CardIdentity.Known.of(code),
                    new ZoneRef(ZoneKind.HAND, PlayerSide.SELF, sequence));
        }
    };

    @Test
    void decodesAnnounceCardAsRpnOpcodes() {
        // ANNOUNCE_CARD 的 payload 是 opcodes（RPN 条件），不是候选卡号。
        // 样例：[额外卡组怪兽类型掩码, OPCODE_ISTYPE, OPCODE_NOT]
        byte[] payload = new ByteWriter()
                .u8(0)          // player
                .u8(3)          // count
                .u32(75505728L) // type mask: LINK|XYZ|SYNCHRO|FUSION
                .u32(1073742082L) // OPCODE_ISTYPE
                .u32(1073741831L) // OPCODE_NOT
                .toByteArray();

        ChoiceRequest request = new RequestDecoder(MIRROR)
                .decode(ProtocolIds.MSG_ANNOUNCE_CARD, new ByteReader(payload));

        var announce = (ChoiceRequest.DeclareCardRequest) request;
        assertEquals(List.of(75505728L, 1073742082L, 1073741831L), announce.opcodes());
    }

    /** 坐 1 号位（后手）的解码器：位图 0–15 是应答者自己，16–31 是对手。 */
    private static RequestDecoder.MirrorView seatOne() {
        return new RequestDecoder.MirrorView() {
            @Override
            public int selfSeat() {
                return 1;
            }

            @Override
            public ChoiceRequest.CardChoice choice(int absController, int location, int sequence, long code) {
                return new ChoiceRequest.CardChoice(0, CardIdentity.Known.of(code),
                        new ZoneRef(ZoneKind.HAND, PlayerSide.SELF, sequence));
            }
        };
    }

    @Test
    void fieldSpellPlaceIsNotZeroCandidates() {
        // 回归：发动场地魔法时，可用位图只剩场地魔法区那一位。
        // 注意：位图**相对应答者**——应答者自己的场地魔法区是 bit 7（不是 bit 23）。
        int flag = 0xFFFFFFFF & ~(1 << 7);
        byte[] payload = new ByteWriter().u8(1).u8(1).u32(flag).toByteArray();

        ChoiceRequest request = new RequestDecoder(seatOne())
                .decode(ProtocolIds.MSG_SELECT_PLACE, new ByteReader(payload));

        var place = (ChoiceRequest.PlaceRequest) request;
        assertEquals(1, place.availableZones().size(), "场地魔法区必须是一个合法候选");
        ZoneRef zone = place.availableZones().getFirst();
        assertEquals(ZoneKind.FIELD_ZONE, zone.kind());
        assertEquals(PlayerSide.SELF, zone.owner());
    }

    @Test
    void ownSpellTrapZonesAreNotLabelledAsOpponentWhenSittingSecond() {
        int flag = 0xFFFFFFFF & ~((1 << 8) | (1 << 9) | (1 << 10) | (1 << 11) | (1 << 12));
        byte[] payload = new ByteWriter().u8(1).u8(1).u32(flag).toByteArray();

        var place = (ChoiceRequest.PlaceRequest) new RequestDecoder(seatOne())
                .decode(ProtocolIds.MSG_SELECT_PLACE, new ByteReader(payload));

        assertEquals(5, place.availableZones().size());
        place.availableZones().forEach(z -> {
            assertEquals(ZoneKind.SPELL_TRAP_ZONE, z.kind());
            assertEquals(PlayerSide.SELF, z.owner(), "应答者自己的格子不能被标成对方");
        });
    }

    @Test
    void highBitsAreTheOpponentOfTheResponder() {
        // 同一张掩码，后半段（16–31）= 应答者的对手：坐 1 号位时 bit 23 是对手的场地魔法区。
        int flag = 0xFFFFFFFF & ~(1 << 23);
        byte[] payload = new ByteWriter().u8(1).u8(1).u32(flag).toByteArray();

        var place = (ChoiceRequest.PlaceRequest) new RequestDecoder(seatOne())
                .decode(ProtocolIds.MSG_SELECT_PLACE, new ByteReader(payload));

        assertEquals(1, place.availableZones().size());
        assertEquals(PlayerSide.OPPONENT, place.availableZones().getFirst().owner());
        assertEquals(ZoneKind.FIELD_ZONE, place.availableZones().getFirst().kind());
    }

    @Test
    void emptyPlaceBitmapFallsBackToMirrorInsteadOfZeroCandidates() {
        // 兜底必须给至少一个候选，哪怕只是"我方第一个空的主怪兽区"。
        RequestDecoder.MirrorView withEmptyZone = new RequestDecoder.MirrorView() {
            @Override
            public int selfSeat() {
                return 0;
            }

            @Override
            public ChoiceRequest.CardChoice choice(int absController, int location, int sequence, long code) {
                return new ChoiceRequest.CardChoice(0, CardIdentity.Known.of(code),
                        new ZoneRef(ZoneKind.HAND, PlayerSide.SELF, sequence));
            }

            @Override
            public ZoneRef firstEmptyZone(ZoneKind kind) {
                return kind == ZoneKind.MAIN_MONSTER_ZONE
                        ? ZoneRef.mainMonster(PlayerSide.SELF, 2) : null;
            }
        };
        byte[] payload = new ByteWriter().u8(0).u8(1).u32(0xFFFFFFFFL).toByteArray();

        ChoiceRequest request = new RequestDecoder(withEmptyZone)
                .decode(ProtocolIds.MSG_SELECT_PLACE, new ByteReader(payload));

        var place = (ChoiceRequest.PlaceRequest) request;
        assertEquals(1, place.availableZones().size(), "0 候选会让对局卡死，必须兜底出一个空位");
        assertEquals(ZoneKind.MAIN_MONSTER_ZONE, place.availableZones().getFirst().kind());
    }

    @Test
    void bitmapBitLayoutCoversFieldZoneAndSeats() {
        assertEquals(new ZoneMapper.Slot(ZoneMapper.LOC_MZONE, 0),
                ZoneMapper.slotFromBitmapBit(0));
        assertEquals(new ZoneMapper.Slot(ZoneMapper.LOC_FZONE, 0),
                ZoneMapper.slotFromBitmapBit(7), "bit 7 = 座位 0 的场地魔法区");
        assertEquals(new ZoneMapper.Slot(ZoneMapper.LOC_FZONE, 0),
                ZoneMapper.slotFromBitmapBit(23), "bit 23 = 座位 1 的场地魔法区");
        assertEquals(new ZoneMapper.Slot(ZoneMapper.LOC_SZONE, 0),
                ZoneMapper.slotFromBitmapBit(8));
        assertEquals(0, ZoneMapper.seatFromBitmapBit(7));
        assertEquals(1, ZoneMapper.seatFromBitmapBit(23));
        assertEquals(7, ZoneMapper.bitmapBit(ZoneMapper.LOC_FZONE, 0, false));
        assertEquals(23, ZoneMapper.bitmapBit(ZoneMapper.LOC_FZONE, 0, true));
    }
}
