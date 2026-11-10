package com.github.coldgust.ygoprosdk.core;

import com.github.coldgust.ygodomain.card.Attribute;
import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.card.MonsterRace;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.duel.Position;
import com.github.coldgust.ygodomain.zone.ZoneKind;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoprosdk.protocol.ByteReader;
import com.github.coldgust.ygoprosdk.protocol.ProtocolIds;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 请求解码：MSG → 领域 {@link ChoiceRequest}（规范 §8）。
 *
 * <p>解码只依赖协议字节 + 当前镜像（用于把 code + 位置解析成实例引用）。
 * 所有请求的 player 字段在会话层已确认等于我方座位。
 */
public final class RequestDecoder {

    private static final Logger log =
            LoggerFactory.getLogger(RequestDecoder.class);

    /** 镜像查询视图：由 {@link StateProjector} 提供。 */
    public interface MirrorView {
        int selfSeat();

        /** 解析候选卡：code + 打包位置 → 卡引用（镜像中存在则引用实例，否则独立构造）。 */
        ChoiceRequest.CardChoice choice(int absController, int location, int sequence, long code);

        /**
         * 我方某类区域的第一个**空**槽位；拿不到就返回 null。
         * <p>只用于位置请求解不出任何可用区域时的兜底——宁可猜一个空位，也不要把 0 候选
         * 发给决策层（那会让应答非法、对局卡死）。
         */
        default ZoneRef firstEmptyZone(ZoneKind kind) {
            return null;
        }
    }

    private final MirrorView mirror;

    public RequestDecoder(MirrorView mirror) {
        this.mirror = mirror;
    }

    /** 解码请求体（不含 type 字节）。 */
    public ChoiceRequest decode(int msgType, ByteReader r) {
        return switch (msgType) {
            case ProtocolIds.MSG_SELECT_IDLECMD -> decodeIdle(r);
            case ProtocolIds.MSG_SELECT_BATTLECMD -> decodeBattle(r);
            case ProtocolIds.MSG_SELECT_CARD -> decodeCard(r);
            case ProtocolIds.MSG_SELECT_TRIBUTE -> decodeTribute(r);
            case ProtocolIds.MSG_SELECT_UNSELECT_CARD -> decodeUnselect(r);
            case ProtocolIds.MSG_SELECT_SUM -> decodeSum(r);
            case ProtocolIds.MSG_SELECT_CHAIN -> decodeChain(r);
            case ProtocolIds.MSG_SELECT_PLACE, ProtocolIds.MSG_SELECT_DISFIELD -> decodePlace(r, msgType);
            case ProtocolIds.MSG_SELECT_POSITION -> decodePosition(r);
            case ProtocolIds.MSG_SELECT_EFFECTYN -> decodeEffectYesNo(r);
            case ProtocolIds.MSG_SELECT_YESNO -> decodeYesNo(r);
            case ProtocolIds.MSG_SELECT_OPTION -> decodeOption(r);
            case ProtocolIds.MSG_ANNOUNCE_RACE -> decodeRace(r);
            case ProtocolIds.MSG_ANNOUNCE_ATTRIB -> decodeAttribute(r);
            case ProtocolIds.MSG_ANNOUNCE_CARD -> decodeAnnounceCard(r);
            case ProtocolIds.MSG_ANNOUNCE_NUMBER -> decodeAnnounceNumber(r);
            case ProtocolIds.MSG_SORT_CARD -> decodeSort(r);
            case ProtocolIds.MSG_SELECT_COUNTER -> decodeCounter(r);
            default -> throw new IllegalArgumentException(
                    "非请求型消息: 0x%02X".formatted(msgType));
        };
    }


    private ChoiceRequest decodeIdle(ByteReader r) {
        r.u8(); // player
        var normal = readCardGroup(r, 7);
        var special = readCardGroup(r, 7);
        var reposition = readCardGroup(r, 7);
        var mset = readCardGroup(r, 7);
        var sset = readCardGroup(r, 7);
        int n6 = r.u8();
        List<ChoiceRequest.EffectChoice> activatable = new ArrayList<>(n6);
        for (int i = 0; i < n6; i++) {
            long code = r.u32();
            int c = r.u8();
            int l = r.u8();
            int s = r.u8();
            long desc = r.u32();
            activatable.add(new ChoiceRequest.EffectChoice(choice(c, l, s, code), desc));
        }
        boolean toBp = r.u8() == 1;
        boolean toEp = r.u8() == 1;
        boolean canShuffle = r.u8() == 1;
        return new ChoiceRequest.MainPhaseRequest(normal, special, reposition, mset, sset,
                activatable, toBp, toEp, canShuffle);
    }

    private List<ChoiceRequest.CardChoice> readCardGroup(ByteReader r, int entrySize) {
        int n = r.u8();
        List<ChoiceRequest.CardChoice> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long code = r.u32();
            int c = r.u8();
            int l = r.u8();
            int s = r.u8();
            if (entrySize == 11) {
                r.u8(); // releaseParam / direct 等（调用处按需读取）
            }
            out.add(choice(c, l, s, code));
        }
        return out;
    }


    private ChoiceRequest decodeBattle(ByteReader r) {
        r.u8(); // player
        int n1 = r.u8();
        List<ChoiceRequest.EffectChoice> activatable = new ArrayList<>(n1);
        for (int i = 0; i < n1; i++) {
            long code = r.u32();
            int c = r.u8();
            int l = r.u8();
            int s = r.u8();
            long desc = r.u32();
            activatable.add(new ChoiceRequest.EffectChoice(choice(c, l, s, code), desc));
        }
        int n2 = r.u8();
        List<ChoiceRequest.AttackChoice> attackable = new ArrayList<>(n2);
        for (int i = 0; i < n2; i++) {
            long code = r.u32();
            int c = r.u8();
            int l = r.u8();
            int s = r.u8();
            boolean direct = r.u8() == 1;
            attackable.add(new ChoiceRequest.AttackChoice(choice(c, l, s, code), direct));
        }
        boolean toM2 = r.u8() == 1;
        boolean toEp = r.u8() == 1;
        return new ChoiceRequest.BattlePhaseRequest(activatable, attackable, toM2, toEp);
    }


    private ChoiceRequest decodeCard(ByteReader r) {
        r.u8(); // player
        boolean cancelable = r.u8() == 1;
        int min = r.u8();
        int max = r.u8();
        int n = r.u8();
        List<ChoiceRequest.CardChoice> candidates = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long code = r.u32();
            ZoneMapper.Packed loc = ZoneMapper.unpack(r.u32());
            candidates.add(choice(loc.controller(), loc.location(), loc.sequence(), code));
        }
        return new ChoiceRequest.CardSelectRequest(min, max, cancelable, candidates);
    }

    private ChoiceRequest decodeTribute(ByteReader r) {
        r.u8(); // player
        boolean cancelable = r.u8() == 1;
        int min = r.u8();
        int max = r.u8();
        int n = r.u8();
        List<ChoiceRequest.TributeChoice> candidates = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long code = r.u32();
            int c = r.u8();
            int l = r.u8();
            int s = r.u8();
            int release = r.u8();
            candidates.add(new ChoiceRequest.TributeChoice(choice(c, l, s, code), release));
        }
        return new ChoiceRequest.TributeSelectRequest(min, max, cancelable, candidates);
    }

    private ChoiceRequest decodeUnselect(ByteReader r) {
        r.u8(); // player
        boolean finishable = r.u8() == 1;
        boolean cancelable = r.u8() == 1;
        int min = r.u8();
        int max = r.u8();
        int n1 = r.u8();
        List<ChoiceRequest.CardChoice> selectable = new ArrayList<>(n1);
        for (int i = 0; i < n1; i++) {
            long code = r.u32();
            ZoneMapper.Packed loc = ZoneMapper.unpack(r.u32());
            selectable.add(choice(loc.controller(), loc.location(), loc.sequence(), code));
        }
        int n2 = r.u8();
        List<ChoiceRequest.CardChoice> selected = new ArrayList<>(n2);
        for (int i = 0; i < n2; i++) {
            long code = r.u32();
            ZoneMapper.Packed loc = ZoneMapper.unpack(r.u32());
            selected.add(choice(loc.controller(), loc.location(), loc.sequence(), code));
        }
        return new ChoiceRequest.UnselectRequest(finishable, cancelable, min, max, selectable, selected);
    }

    private ChoiceRequest decodeSum(ByteReader r) {
        boolean unbounded = r.u8() == 1;
        r.u8(); // player
        int acc = r.i32();
        int min = r.u8();
        int max = r.u8();
        int nm = r.u8();
        List<ChoiceRequest.CardChoice> must = new ArrayList<>(nm);
        for (int i = 0; i < nm; i++) {
            must.add(readSumEntry(r));
        }
        int no = r.u8();
        List<ChoiceRequest.CardChoice> optional = new ArrayList<>(no);
        for (int i = 0; i < no; i++) {
            optional.add(readSumEntry(r));
        }
        return new ChoiceRequest.SumRequest(unbounded, acc, min, max, must, optional);
    }

    private ChoiceRequest.CardChoice readSumEntry(ByteReader r) {
        long code = r.u32();
        int c = r.u8();
        int l = r.u8();
        int s = r.u8();
        r.u32(); // sumParam（数值和的加权参数，决策层可再实现）
        return choice(c, l, s, code);
    }


    private ChoiceRequest decodeChain(ByteReader r) {
        r.u8(); // player
        int n = r.u8();
        r.u8(); // speCount
        r.u32(); // hintTimingSelf
        r.u32(); // hintTimingOpp
        List<ChoiceRequest.ChainRequest.ChainOption> options = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            // 容错：YGOPro 系服务端对 count≥2 的 SELECT_CHAIN 会按自身长度模型截断转发，
            // 选项数据可能不足。读不满时补"未知、非强制"选项占位，保证请求仍可应答（decline 不依赖选项内容）。
            if (!r.canRead(14)) {
                options.add(new ChoiceRequest.ChainRequest.ChainOption(
                        new ChoiceRequest.EffectChoice(choice(0, 0, 0, 0), 0), false));
                continue;
            }
            r.u8();  // descType
            boolean forced = r.u8() == 1;
            long code = r.u32();
            ZoneMapper.Packed loc = ZoneMapper.unpack(r.u32());
            long desc = r.u32();
            options.add(new ChoiceRequest.ChainRequest.ChainOption(
                    new ChoiceRequest.EffectChoice(choice(loc.controller(), loc.location(), loc.sequence(), code), desc),
                    forced));
        }
        return new ChoiceRequest.ChainRequest(options);
    }


    private ChoiceRequest decodePlace(ByteReader r, int msgType) {
        int responder = r.u8(); // player：本条请求要谁应答（选择位置几乎总是我们）
        int count = r.u8();
        int flag = (int) r.u32();
        // flag 是"不可用"掩码，取补集。
        List<ZoneRef> available = collectPlaceZones(flag, false, responder);
        if (available.isEmpty()) {
            // 兜底一：按镜像空位猜（绝大多数情况是对的——引擎只是没给出可读的位图）
            available = guessFromMirror(count);
            if (!available.isEmpty()) {
                log.warn("位置请求按\"不可用掩码\"解出 0 候选（flag=0x{}），按镜像空位兜底为 {}",
                        Integer.toHexString(flag), available);
            } else {
                // 兜底二：万一某条消息的 flag 极性相反（置位=可用），按置位再解一次。
                // 宁可猜也不要把 0 候选发给决策层——那会让应答非法、对局永久卡住。
                available = collectPlaceZones(flag, true, responder);
                log.warn("位置请求解不出任何可用区域（flag=0x{}），改按置位解释，得 {} 个",
                        Integer.toHexString(flag), available.size());
            }
        }
        if (msgType == ProtocolIds.MSG_SELECT_PLACE) {
            return new ChoiceRequest.PlaceRequest(count, available);
        }
        return new ChoiceRequest.DisfieldRequest(count, available);
    }

    /**
     * @param responder 本条请求的应答者绝对座位（消息里的 player 字段）
     */
    private List<ZoneRef> collectPlaceZones(int flag, boolean bitsAreAvailable, int responder) {
        List<ZoneRef> zones = new ArrayList<>();
        int self = mirror.selfSeat();
        for (int bit = 0; bit < 32; bit++) {
            boolean usable = (flag & (1 << bit)) != 0;
            if (usable != bitsAreAvailable) {
                continue;
            }
            ZoneMapper.Slot slot = ZoneMapper.slotFromBitmapBit(bit);
            if (slot == null) {
                continue;
            }
            // 位图归属相对应答者：高位段 = 应答者的对手。
            int bitSeat = bit >= 16 ? 1 - responder : responder;
            var owner = bitSeat == self
                    ? PlayerSide.SELF
                    : PlayerSide.OPPONENT;
            ZoneRef zone = ZoneMapper.toZone(self, slot.location(), slot.sequence(), owner);
            if (zone != null) {
                zones.add(zone);
            }
        }
        return zones;
    }

    /**
     * 最后的兜底：位图完全解不出来时，从我方空位里挑（怪兽区 → 魔陷区 → 场地魔法区）。
     * <p>优先级按"最可能被问到的区域"排：绝大多数位置请求都是放怪兽或盖魔陷。
     */
    private List<ZoneRef> guessFromMirror(int count) {
        List<ZoneRef> out = new ArrayList<>();
        int need = Math.max(1, count);
        for (var kind : List.of(ZoneKind.MAIN_MONSTER_ZONE,
                ZoneKind.SPELL_TRAP_ZONE,
                ZoneKind.FIELD_ZONE)) {
            ZoneRef zone = mirror.firstEmptyZone(kind);
            if (zone != null) {
                out.add(zone);
                if (out.size() >= need) {
                    break;
                }
            }
        }
        return out;
    }

    private ChoiceRequest decodePosition(ByteReader r) {
        r.u8(); // player
        long code = r.u32();
        int mask = r.u8();
        Set<Position> positions = EnumSet.noneOf(Position.class);
        if ((mask & ZoneMapper.POS_FACEUP_ATTACK) != 0) positions.add(Position.FACE_UP_ATTACK);
        if ((mask & ZoneMapper.POS_FACEDOWN_ATTACK) != 0) positions.add(Position.FACE_DOWN_ATTACK);
        if ((mask & ZoneMapper.POS_FACEUP_DEFENSE) != 0) positions.add(Position.FACE_UP_DEFENSE);
        if ((mask & ZoneMapper.POS_FACEDOWN_DEFENSE) != 0) positions.add(Position.FACE_DOWN_DEFENSE);
        return new ChoiceRequest.PositionRequest(choice(mirror.selfSeat(), ZoneMapper.LOC_HAND, 0, code), positions);
    }


    private ChoiceRequest decodeEffectYesNo(ByteReader r) {
        r.u8(); // player
        long code = r.u32();
        ZoneMapper.Packed loc = ZoneMapper.unpack(r.u32());
        long desc = r.u32();
        return new ChoiceRequest.EffectYesNoRequest(
                choice(loc.controller(), loc.location(), loc.sequence(), code), desc);
    }

    private ChoiceRequest decodeYesNo(ByteReader r) {
        r.u8(); // player
        long desc = r.u32();
        return new ChoiceRequest.YesNoRequest(desc);
    }

    private ChoiceRequest decodeOption(ByteReader r) {
        r.u8(); // player
        int n = r.u8();
        List<Long> options = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            options.add(r.u32());
        }
        return new ChoiceRequest.OptionRequest(options);
    }


    private ChoiceRequest decodeRace(ByteReader r) {
        r.u8(); // player
        int count = r.u8();
        int mask = (int) r.u32();
        Set<MonsterRace> available = EnumSet.noneOf(MonsterRace.class);
        for (var e : RaceBits.BY_BIT.entrySet()) {
            if ((mask & e.getKey()) != 0) {
                available.add(e.getValue());
            }
        }
        return new ChoiceRequest.DeclareRaceRequest(count, available);
    }

    private ChoiceRequest decodeAttribute(ByteReader r) {
        r.u8(); // player
        int count = r.u8();
        int mask = (int) r.u32();
        Set<Attribute> available = EnumSet.noneOf(Attribute.class);
        for (var e : AttributeBits.BY_BIT.entrySet()) {
            if ((mask & e.getKey()) != 0) {
                available.add(e.getValue());
            }
        }
        return new ChoiceRequest.DeclareAttributeRequest(count, available);
    }

    private ChoiceRequest decodeAnnounceCard(ByteReader r) {
        r.u8(); // player
        int n = r.u8();
        List<Long> opcodes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            opcodes.add(r.u32());
        }
        return new ChoiceRequest.DeclareCardRequest(opcodes);
    }

    private ChoiceRequest decodeAnnounceNumber(ByteReader r) {
        r.u8(); // player
        int n = r.u8();
        List<Integer> numbers = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            numbers.add((int) r.u32());
        }
        return new ChoiceRequest.DeclareNumberRequest(numbers);
    }


    private ChoiceRequest decodeSort(ByteReader r) {
        r.u8(); // player
        int n = r.u8();
        List<ChoiceRequest.CardChoice> cards = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long code = r.u32();
            int c = r.u8();
            int l = r.u8();
            int s = r.u8();
            cards.add(choice(c, l, s, code));
        }
        return new ChoiceRequest.SortRequest(cards);
    }

    private ChoiceRequest decodeCounter(ByteReader r) {
        r.u8(); // player
        int counterType = r.i16() & 0xFFFF;
        int total = r.i16() & 0xFFFF;
        int n = r.u8();
        List<ChoiceRequest.CounterRequest.CounterChoice> candidates = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            long code = r.u32();
            int c = r.u8();
            int l = r.u8();
            int s = r.u8();
            int available = r.i16() & 0xFFFF;
            candidates.add(new ChoiceRequest.CounterRequest.CounterChoice(choice(c, l, s, code), available));
        }
        return new ChoiceRequest.CounterRequest(counterType, total, candidates);
    }


    private ChoiceRequest.CardChoice choice(int absController, int location, int sequence, long code) {
        return mirror.choice(absController, location, sequence, code);
    }

    private CardIdentity identity(long code) {
        return code == 0 ? CardIdentity.UNKNOWN : new CardIdentity.Known(code, null);
    }

    /** 协议种族位（ygopro-core common.h RACE_ 常量，规范 §8.12）。 */
    static final class RaceBits {
        static final Map<Integer, MonsterRace> BY_BIT = new LinkedHashMap<>();

        static {
            put(0x1, MonsterRace.DRAGON);
            put(0x2, MonsterRace.SPELLCASTER);
            put(0x4, MonsterRace.WARRIOR);
            put(0x8, MonsterRace.BEAST_WARRIOR);
            put(0x10, MonsterRace.THUNDER);
            put(0x20, MonsterRace.AQUA);
            put(0x40, MonsterRace.PYRO);
            put(0x80, MonsterRace.ROCK);
            put(0x100, MonsterRace.WINGED_BEAST);
            put(0x200, MonsterRace.PLANT);
            put(0x400, MonsterRace.INSECT);
            put(0x800, MonsterRace.MACHINE);
            put(0x1000, MonsterRace.REPTILE);
            put(0x2000, MonsterRace.FISH);
            put(0x4000, MonsterRace.SEA_SERPENT);
            put(0x8000, MonsterRace.ZOMBIE);
            put(0x10000, MonsterRace.DINOSAUR);
            put(0x20000, MonsterRace.FAIRY);
            put(0x40000, MonsterRace.FIEND);
            put(0x80000, MonsterRace.BEAST);
            put(0x100000, MonsterRace.DIVINE_BEAST);
            put(0x200000, MonsterRace.CREATOR);
            put(0x400000, MonsterRace.WYRM);
            put(0x800000, MonsterRace.CYBERSE);
            put(0x1000000, MonsterRace.ILLUSION);
        }

        static int bitOf(MonsterRace race) {
            for (var e : BY_BIT.entrySet()) {
                if (e.getValue() == race) {
                    return e.getKey();
                }
            }
            return 0;
        }

        private static void put(int bit, MonsterRace race) {
            BY_BIT.put(bit, race);
        }

        private RaceBits() {
        }
    }

    /** 协议属性位。 */
    static final class AttributeBits {
        static final Map<Integer, Attribute> BY_BIT = new LinkedHashMap<>();

        static {
            BY_BIT.put(0x1, Attribute.FIRE);
            BY_BIT.put(0x2, Attribute.WIND);
            BY_BIT.put(0x4, Attribute.EARTH);
            BY_BIT.put(0x8, Attribute.WATER);
            BY_BIT.put(0x10, Attribute.LIGHT);
            BY_BIT.put(0x20, Attribute.DARK);
            BY_BIT.put(0x40, Attribute.DIVINE);
        }

        static int bitOf(Attribute attribute) {
            for (var e : BY_BIT.entrySet()) {
                if (e.getValue() == attribute) {
                    return e.getKey();
                }
            }
            return 0;
        }

        private AttributeBits() {
        }
    }
}
