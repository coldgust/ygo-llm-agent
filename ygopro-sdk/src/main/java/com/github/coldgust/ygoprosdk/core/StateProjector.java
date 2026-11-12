package com.github.coldgust.ygoprosdk.core;

import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.duel.CardInstance;
import com.github.coldgust.ygodomain.duel.ChainNode;
import com.github.coldgust.ygodomain.duel.DuelResult;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.Phase;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.duel.PlayerState;
import com.github.coldgust.ygodomain.duel.WinReason;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoprosdk.protocol.ByteReader;
import com.github.coldgust.ygoprosdk.protocol.ProtocolIds;
import com.github.coldgust.ygoprosdk.spi.CardInfoResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 战场镜像（规范 §9.3 事件 → 状态投影表）。
 *
 * <p>内部为可变构建态；对外通过 {@link #snapshot()} 产出不可变的 {@link DuelState}
 * （全 record，equals/hashCode 稳定）。
 *
 * <p>视角：内部以绝对座位（0/1）记账，快照换算为 SELF / OPPONENT 相对视角；
 * 座位以 {@code MSG_START} 首字节为唯一权威（§7.3.2）。
 */
public final class StateProjector {

    private static final class Card {
        long id;                 // 镜像内实例标识（0 = 未注册）
        CardIdentity identity;
        int controller;          // 绝对座位
        int location;            // 协议 location 字节
        int sequence;
        int position;            // 协议 position 字节（非场地区域为 0）
        final Map<Long, Integer> counters = new TreeMap<>();
        final List<Long> xyzMaterials = new ArrayList<>();
    }

    private static final class Player {
        String name = "";
        int lp;
        final List<Long> hand = new ArrayList<>();   // instanceId 列表
        int mainDeckCount;
        int extraDeckCount;
        final List<CardIdentity> graveyard = new ArrayList<>();
        final List<CardIdentity> banished = new ArrayList<>();
        final List<CardIdentity> extraDeck = new ArrayList<>();
    }

    private final CardInfoResolver resolver;

    private int selfSeat = -1;
    private int duelRule;
    private int turnNumber;
    private int turnSeat = -1;
    private int phaseBits;
    private DuelResult result;

    private final Map<Integer, Player> players = new LinkedHashMap<>();
    private final Map<Long, Card> cards = new LinkedHashMap<>();
    private final Map<Long, Long> zoneIndex = new LinkedHashMap<>(); // zoneKey -> instanceId
    private final List<ChainNode> chain = new ArrayList<>();
    /** 先到、还没挂到任何一环上的对象（协议顺序不保证先 CHAINING 后 BECOME_TARGET）。 */
    private final List<ZoneRef> pendingTargets = new ArrayList<>();
    private long nextInstanceId = 1;

    public StateProjector(CardInfoResolver resolver) {
        this.resolver = resolver;
    }

    public int selfSeat() {
        return selfSeat;
    }


    /**
     * 应用一条 MSG（payload 为去掉 type 字节后的消息体）。
     */
    public synchronized void apply(int msgType, ByteReader r) {
        switch (msgType) {
            case ProtocolIds.MSG_START -> onStart(r);
            case ProtocolIds.MSG_WIN -> onWin(r);
            case ProtocolIds.MSG_NEW_TURN -> onNewTurn(r);
            case ProtocolIds.MSG_NEW_PHASE -> onNewPhase(r);
            case ProtocolIds.MSG_DRAW -> onDraw(r);
            case ProtocolIds.MSG_MOVE -> onMove(r);
            case ProtocolIds.MSG_SET -> onSet(r);
            case ProtocolIds.MSG_POS_CHANGE -> onPosChange(r);
            case ProtocolIds.MSG_SWAP -> onSwap(r);
            case ProtocolIds.MSG_SUMMONING, ProtocolIds.MSG_SPSUMMONING, ProtocolIds.MSG_FLIPSUMMONING ->
                    onPlacing(r);
            case ProtocolIds.MSG_DAMAGE -> onLpDelta(r, -1);
            case ProtocolIds.MSG_RECOVER -> onLpDelta(r, +1);
            case ProtocolIds.MSG_PAY_LPCOST -> onLpDelta(r, -1);
            case ProtocolIds.MSG_LPUPDATE -> onLpUpdate(r);
            case ProtocolIds.MSG_SHUFFLE_HAND -> onShuffleHand(r);
            case ProtocolIds.MSG_CONFIRM_CARDS -> onConfirmCards(r);
            case ProtocolIds.MSG_CHAINING -> onChaining(r);
            case ProtocolIds.MSG_BECOME_TARGET -> onBecomeTarget(r);
            case ProtocolIds.MSG_CHAIN_END -> {
                chain.clear();
                pendingTargets.clear();
            }
            case ProtocolIds.MSG_CHAIN_NEGATED, ProtocolIds.MSG_CHAIN_DISABLED -> onChainNegated(r);
            case ProtocolIds.MSG_ADD_COUNTER -> onCounter(r, +1);
            case ProtocolIds.MSG_REMOVE_COUNTER -> onCounter(r, -1);
            case ProtocolIds.MSG_RELOAD_FIELD -> onReloadField(r);
            case ProtocolIds.MSG_UPDATE_CARD -> onUpdateCard(r);
            case ProtocolIds.MSG_UPDATE_DATA -> onUpdateData(r);
            case ProtocolIds.MSG_SELECT_PLACE -> { /* 纯请求，状态不变 */ }
            default -> {
                // 未参与状态投影的消息：RETRY / HINT / WAITING / BATTLE / ATTACK / TOSS_* 等
            }
        }
    }


    private void onStart(ByteReader r) {
        int seat = r.u8();          // playertype = 接收者座位（§7.3.2）
        selfSeat = seat;
        duelRule = r.u8();
        int lp0 = r.i32();
        int lp1 = r.i32();
        int deck0 = r.i16();
        int extra0 = r.i16();
        int deck1 = r.i16();
        int extra1 = r.i16();

        players.clear();
        cards.clear();
        zoneIndex.clear();
        chain.clear();
        result = null;
        turnNumber = 0;
        phaseBits = 0;

        Player p0 = new Player();
        p0.lp = lp0;
        p0.mainDeckCount = deck0;
        p0.extraDeckCount = extra0;
        Player p1 = new Player();
        p1.lp = lp1;
        p1.mainDeckCount = deck1;
        p1.extraDeckCount = extra1;
        players.put(0, p0);
        players.put(1, p1);
    }

    private void onWin(ByteReader r) {
        int winnerSeat = r.u8();
        int reason = r.u8();
        PlayerSide winner = switch (reason) {
            case 0 -> winnerSeat <= 1 ? sideOf(winnerSeat) : null;
            case 2 -> null; // 抽死平局
            default -> winnerSeat <= 1 ? sideOf(winnerSeat) : null;
        };
        WinReason wr = switch (reason) {
            case 2 -> WinReason.DRAW;
            case 3 -> WinReason.TIMEOUT;
            case 4 -> WinReason.FORCED;
            default -> WinReason.NORMAL;
        };
        result = new DuelResult(winner, wr);
    }

    private void onNewTurn(ByteReader r) {
        turnSeat = r.u8();
        turnNumber++;
    }

    private void onNewPhase(ByteReader r) {
        phaseBits = r.i16();
    }

    private Phase phase() {
        return switch (phaseBits) {
            case 0x0001 -> Phase.DRAW_PHASE;
            case 0x0002 -> Phase.STANDBY_PHASE;
            case 0x0004 -> Phase.MAIN_PHASE_1;
            case 0x0008 -> Phase.BATTLE_START_STEP;
            case 0x0010 -> Phase.BATTLE_STEP;
            case 0x0020 -> Phase.DAMAGE_STEP;
            case 0x0040 -> Phase.DAMAGE_CALCULATION;
            case 0x0080 -> Phase.BATTLE_END_STEP;
            case 0x0100 -> Phase.MAIN_PHASE_2;
            case 0x0200 -> Phase.END_PHASE;
            default -> Phase.MAIN_PHASE_1;
        };
    }

    private void onDraw(ByteReader r) {
        int seat = r.u8();
        int count = r.u8();
        Player p = players.get(seat);
        if (p == null) {
            return;
        }
        for (int i = 0; i < count; i++) {
            long code = r.u32();
            CardIdentity identity = identityOf(code);
            Card card = new Card();
            card.identity = identity;
            card.controller = seat;
            card.location = ZoneMapper.LOC_HAND;
            card.sequence = 0;
            long id = register(card);
            p.hand.add(id);
        }
        // MSG_DRAW 的语义就是从主卡组抽走：不扣卡组数，[约束] 里的剩余数会一直虚高
        p.mainDeckCount = Math.max(0, p.mainDeckCount - count);
    }

    private void onMove(ByteReader r) {
        long code = r.u32();
        ZoneMapper.Packed from = ZoneMapper.unpack(r.u32());
        ZoneMapper.Packed to = ZoneMapper.unpack(r.u32());
        r.u32(); // reason
        Card card = takeFromZone(from);
        if (card == null && from.location() == ZoneMapper.LOC_HAND) {
            // 手牌不在 zoneIndex 中，takeFromZone 拿不到；必须单独摘出，
            // 否则手牌张数永远不减、同一张卡会同时出现在手牌与场上。
            card = takeFromHand(from.controller(), code);
        }
        if (card == null) {
            card = new Card();
            card.identity = identityOf(code);
        }
        if (code != 0) {
            card.identity = identityOf(code);
        }
        card.controller = to.controller();
        card.location = to.location();
        card.sequence = to.sequence();
        card.position = to.position();
        place(card);
        adjustZoneLists(from, to, card);
    }

    /**
     * 盖放（§9.1：code 对所有人恒为 0）。
     *
     * <p>引擎的"从手牌盖放"实际是两条消息：先 {@code MSG_MOVE}（携带真实卡号，我方可见）
     * 落到目标槽位，再 {@code MSG_SET} 通知该卡已翻为里侧。因此这里<b>必须复用</b>
     * 槽位上已有的实例，只更新表示形式；若新建实例，旧实例不会被清出
     * {@code cards}，同一格就会出现两张卡（3 张盖卡被数成 6 张）。
     */
    private void onSet(ByteReader r) {
        r.u32(); // code：服务端无条件抹零（§9.1），不能得知盖放内容
        ZoneMapper.Packed to = ZoneMapper.unpack(r.u32());
        Card card = findAt(to.controller(), to.location(), to.sequence());
        if (card == null) {
            card = new Card();
            card.identity = CardIdentity.UNKNOWN;
            card.controller = to.controller();
            card.location = to.location();
            card.sequence = to.sequence();
            card.position = to.position() != 0
                    ? to.position() : ZoneMapper.POS_FACEDOWN_DEFENSE;
            place(card);
            adjustZoneLists(null, to, card);
            return;
        }
        // 复用：MSG_SET 语义就是"里侧"，直接定为里侧守备，
        // 不依赖打包位置字（实测引擎该字节常为 0，交给 byte 会把朝向丢掉）
        card.position = to.position() != 0
                ? to.position() : ZoneMapper.POS_FACEDOWN_DEFENSE;
        if (to.controller() != selfSeat) {
            card.identity = CardIdentity.UNKNOWN; // 对方盖卡内容不可见
        }
    }

    private void onPosChange(ByteReader r) {
        r.u32(); // code（对手版本可能抹零）
        int c = r.u8();
        int l = r.u8();
        int s = r.u8();
        r.u8();  // prevPos
        int curPos = r.u8();
        Card card = findAt(c, l, s);
        if (card != null) {
            card.position = curPos;
        }
    }

    private void onSwap(ByteReader r) {
        long code1 = r.u32();
        ZoneMapper.Packed loc1 = ZoneMapper.unpack(r.u32());
        long code2 = r.u32();
        ZoneMapper.Packed loc2 = ZoneMapper.unpack(r.u32());
        Card a = takeFromZone(loc1);
        Card b = takeFromZone(loc2);
        if (a != null) {
            a.controller = loc2.controller();
            a.location = loc2.location();
            a.sequence = loc2.sequence();
            a.position = loc2.position();
            if (code1 != 0) a.identity = identityOf(code1);
            place(a);
            adjustZoneLists(null, loc2, a);
        }
        if (b != null) {
            b.controller = loc1.controller();
            b.location = loc1.location();
            b.sequence = loc1.sequence();
            b.position = loc1.position();
            if (code2 != 0) b.identity = identityOf(code2);
            place(b);
            adjustZoneLists(null, loc1, b);
        }
    }

    private void onPlacing(ByteReader r) {
        long code = r.u32();
        ZoneMapper.Packed loc = ZoneMapper.unpack(r.u32());
        Card card = findAt(loc.controller(), loc.location(), loc.sequence());
        if (card == null) {
            card = new Card();
        }
        if (code != 0) {
            card.identity = identityOf(code);
        }
        card.controller = loc.controller();
        card.location = loc.location();
        card.sequence = loc.sequence();
        card.position = loc.position();
        // 先落到槽位再登记：place() 需要 controller/location/sequence 才能入 zoneIndex，
        // 否则该槽位后续找不到实例，会为同一张卡再建一个副本。
        place(card);
    }

    private void onLpDelta(ByteReader r, int sign) {
        int seat = r.u8();
        int amount = r.i32();
        Player p = players.get(seat);
        if (p != null) {
            p.lp += sign * amount;
        }
    }

    private void onLpUpdate(ByteReader r) {
        int seat = r.u8();
        int lp = (int) r.u32();
        Player p = players.get(seat);
        if (p != null) {
            p.lp = lp;
        }
    }

    private void onShuffleHand(ByteReader r) {
        int seat = r.u8();
        int count = r.u8();
        Player p = players.get(seat);
        if (p == null) {
            return;
        }
        List<Long> newOrder = new ArrayList<>(count);
        List<Long> pool = new ArrayList<>(p.hand);
        for (int i = 0; i < count; i++) {
            long code = r.u32();
            Long matched = null;
            for (Long id : pool) {
                Card c = cards.get(id);
                if (c != null && c.identity.passcode() == code) {
                    matched = id;
                    break;
                }
            }
            if (matched == null && !pool.isEmpty()) {
                matched = pool.getFirst();
            }
            if (matched != null) {
                pool.remove(matched);
                newOrder.add(matched);
            }
        }
        newOrder.addAll(pool);
        p.hand.clear();
        p.hand.addAll(newOrder);
        for (int i = 0; i < p.hand.size(); i++) {
            Card c = cards.get(p.hand.get(i));
            if (c != null) {
                c.sequence = 0; // 手牌序号不作为区域坐标使用
            }
        }
    }

    private void onConfirmCards(ByteReader r) {
        r.u8();  // player
        r.u8();  // skipPanel
        int count = r.u8();
        for (int i = 0; i < count; i++) {
            long code = r.u32();
            int c = r.u8();
            int l = r.u8();
            int s = r.u8();
            Card card = findAt(c, l, s);
            if (card != null && code != 0) {
                card.identity = identityOf(code);
            }
        }
    }

    /**
     * 某一环的发动对象（BECOME_TARGET）。
     */
    private void onBecomeTarget(ByteReader r) {
        int count = r.u8();
        List<ZoneRef> targets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ZoneMapper.Packed loc = ZoneMapper.unpack(r.u32());
            targets.add(zoneRef(loc.controller(), loc.location(), loc.sequence()));
        }
        if (targets.isEmpty()) {
            return;
        }
        if (chain.isEmpty()) {
            // 还没见到 CHAINING：先存着，等那一环出现时挂上去
            pendingTargets.addAll(targets);
            return;
        }
        int last = chain.size() - 1;
        chain.set(last, chain.get(last).withTargets(targets));
    }

    /**
     * 某一环被无效（CHAIN_NEGATED / CHAIN_DISABLED）：<b>标记而不是删除</b>。
     */
    private void onChainNegated(ByteReader r) {
        int index = r.u8();
        for (int i = 0; i < chain.size(); i++) {
            if (chain.get(i).index() == index) {
                chain.set(i, chain.get(i).asNegated());
                return;
            }
        }
        // 链号对不上（0 基或异常报文）：退回保守做法，至少不留下一个已作废的尾环
        if (!chain.isEmpty()) {
            chain.remove(chain.size() - 1);
        }
    }

    private void onChaining(ByteReader r) {
        long code = r.u32();
        ZoneMapper.Packed loc = ZoneMapper.unpack(r.u32());
        int controller = r.u8();
        r.u8();  // triggering location
        r.u8();  // triggering sequence
        long desc = r.u32();
        int chainIndex = r.u8();
        ChainNode node = new ChainNode(chainIndex, identityOf(code), desc,
                zoneRef(loc.controller(), loc.location(), loc.sequence()), sideOf(controller));
        if (!pendingTargets.isEmpty()) {
            node = node.withTargets(List.copyOf(pendingTargets));
            pendingTargets.clear();
        }
        chain.add(node);
    }

    private void onCounter(ByteReader r, int sign) {
        int counterType = r.i16() & 0xFFFF;
        int c = r.u8();
        int l = r.u8();
        int s = r.u8();
        int amount = r.i16();
        Card card = findAt(c, l, s);
        if (card != null) {
            card.counters.merge((long) counterType, sign * amount, Integer::sum);
            card.counters.values().removeIf(v -> v <= 0);
        }
    }

    private void onReloadField(ByteReader r) {
        duelRule = r.u8();
        for (int seat = 0; seat <= 1; seat++) {
            Player p = players.computeIfAbsent(seat, k -> new Player());
            p.lp = r.i32();
            // 清空该方全部区域卡
            clearZones(seat);
            for (int seq = 0; seq < 7; seq++) { // mzone
                int present = r.u8();
                if (present == 1) {
                    int pos = r.u8();
                    int xyz = r.u8();
                    Card card = new Card();
                    card.identity = CardIdentity.UNKNOWN;
                    card.controller = seat;
                    card.location = ZoneMapper.LOC_MZONE;
                    card.sequence = seq;
                    card.position = pos;
                    place(card);
                    for (int m = 0; m < xyz; m++) {
                        card.xyzMaterials.add(0L); // 数量占位，code 由 UPDATE_DATA 回填
                    }
                }
            }
            for (int seq = 0; seq < 8; seq++) { // szone
                int present = r.u8();
                if (present == 1) {
                    int pos = r.u8();
                    Card card = new Card();
                    card.identity = CardIdentity.UNKNOWN;
                    card.controller = seat;
                    card.location = ZoneMapper.LOC_SZONE;
                    card.sequence = seq;
                    card.position = pos;
                    place(card);
                }
            }
            p.mainDeckCount = r.u8();
            p.hand.clear();
            int handCount = r.u8();
            for (int i = 0; i < handCount; i++) {
                Card card = new Card();
                card.identity = seat == selfSeat ? CardIdentity.UNKNOWN : CardIdentity.UNKNOWN;
                card.controller = seat;
                card.location = ZoneMapper.LOC_HAND;
                card.sequence = 0;
                p.hand.add(register(card));
            }
            p.graveyard.clear();
            int graveCount = r.u8();
            for (int i = 0; i < graveCount; i++) {
                p.graveyard.add(CardIdentity.UNKNOWN);
            }
            p.banished.clear();
            int removedCount = r.u8();
            for (int i = 0; i < removedCount; i++) {
                p.banished.add(CardIdentity.UNKNOWN);
            }
            p.extraDeck.clear();
            int extraCount = r.u8();
            for (int i = 0; i < extraCount; i++) {
                p.extraDeck.add(CardIdentity.UNKNOWN);
            }
            r.u8(); // extraPendCount
        }
        int chainCount = r.u8();
        chain.clear();
        for (int i = 0; i < chainCount; i++) {
            long code = r.u32();
            ZoneMapper.Packed loc = ZoneMapper.unpack(r.u32());
            int controller = r.u8();
            r.u8();
            r.u8();
            long desc = r.u32();
            chain.add(new ChainNode(i + 1, identityOf(code), desc,
                    zoneRef(loc.controller(), loc.location(), loc.sequence()), sideOf(controller)));
        }
    }

    private void onUpdateCard(ByteReader r) {
        int seat = r.u8();
        int loc = r.u8();
        int seq = r.u8();
        QueryBlockDecoder.CardBlock block = QueryBlockDecoder.decode(r);
        if (block == null) {
            return;
        }
        if (isListZone(loc)) {
            // 列表区域（手牌/墓地/除外等）不在 zoneIndex，findAt 必然落空——
            // 以前这里会新建实例，同一张卡就出现两份（记录翻倍、实例计数虚高）。
            // 只做身份回填，不建实例。
            backfillListIdentity(seat, loc, seq, block.code());
            return;
        }
        Card card = findAt(seat, loc, seq);
        if (card == null) {
            card = new Card();
            card.controller = seat;
            card.location = loc;
            card.sequence = seq;
            place(card);
        }
        if (block.code() != 0) {
            card.identity = identityOf(block.code());
        }
        if (block.position() != null && loc == ZoneMapper.LOC_MZONE || loc == ZoneMapper.LOC_SZONE) {
            card.position = block.position();
        }
    }

    /** 是否为"列表区域"（卡以身份列表记账，不占槽位索引）。 */
    private static boolean isListZone(int loc) {
        return loc == ZoneMapper.LOC_DECK || loc == ZoneMapper.LOC_HAND
                || loc == ZoneMapper.LOC_GRAVE || loc == ZoneMapper.LOC_REMOVED
                || loc == ZoneMapper.LOC_EXTRA;
    }

    /**
     * 把引擎同步的卡号回填进列表区域：
     * 手牌更新对应实例的身份；墓地/除外/额外更新身份列表（公开信息， revealed 即已知）。
     */
    private void backfillListIdentity(int seat, int loc, int seq, long code) {
        if (code == 0) {
            return;
        }
        Player p = players.get(seat);
        if (p == null) {
            return;
        }
        CardIdentity identity = identityOf(code);
        switch (loc) {
            case ZoneMapper.LOC_HAND -> {
                if (seq < p.hand.size()) {
                    Card card = cards.get(p.hand.get(seq));
                    if (card != null) {
                        card.identity = identity;
                    }
                }
            }
            case ZoneMapper.LOC_GRAVE -> setOrAdd(p.graveyard, seq, identity);
            case ZoneMapper.LOC_REMOVED -> setOrAdd(p.banished, seq, identity);
            case ZoneMapper.LOC_EXTRA -> setOrAdd(p.extraDeck, seq, identity);
            default -> {
                // 主卡组不跟踪逐卡身份，无可回填
            }
        }
    }

    private static void setOrAdd(List<CardIdentity> list, int index, CardIdentity identity) {
        if (index < list.size()) {
            list.set(index, identity);
        } else {
            list.add(identity);
        }
    }

    private void onUpdateData(ByteReader r) {
        int seat = r.u8();
        int loc = r.u8();
        Player p = players.get(seat);
        int index = 0;
        while (r.remaining() >= 4) {
            int blockStart = r.position();
            // 块长含长度字段自身（空槽位 = 4）。只读一次，块边界才不会被推错一格：
            // 多读一次会让后续所有块的字段整体前移，位置打包字被当成卡号（卡名成了 0x0A000200）。
            int length = r.i32();
            if (length < 4) {
                break;
            }
            QueryBlockDecoder.CardBlock block = length <= 8
                    ? null : QueryBlockDecoder.decodeBody(r, length);
            if (block != null) {
                // 列表区域一律不建实例（手牌/墓地等不在 zoneIndex，findAt 落空后新建
                // 会与真实实例并存——记录翻倍、实例计数虚高），只回填身份
                Card card = isListZone(loc) ? null : findAt(seat, loc, index);
                if (card == null && !isListZone(loc)) {
                    card = new Card();
                    card.controller = seat;
                    card.location = loc;
                    card.sequence = index;
                    place(card);
                }
                if (card != null && block.code() != 0) {
                    card.identity = identityOf(block.code());
                }
                if (card != null && block.position() != null) {
                    card.position = block.position();
                }
                if (loc == ZoneMapper.LOC_HAND && p != null) {
                    // 手牌块按序重建（自身为真实卡号，对方全 0 = 未知卡）
                    if (p.hand.size() <= index) {
                        Card card2 = new Card();
                        card2.controller = seat;
                        card2.location = ZoneMapper.LOC_HAND;
                        p.hand.add(register(card2));
                    }
                    Card handCard = cards.get(p.hand.get(index));
                    if (handCard != null && block.code() != 0) {
                        handCard.identity = identityOf(block.code());
                    }
                }
                if (p != null && block.code() != 0) {
                    if (loc == ZoneMapper.LOC_GRAVE) {
                        setOrAdd(p.graveyard, index, identityOf(block.code()));
                    } else if (loc == ZoneMapper.LOC_REMOVED) {
                        setOrAdd(p.banished, index, identityOf(block.code()));
                    } else if (loc == ZoneMapper.LOC_EXTRA) {
                        setOrAdd(p.extraDeck, index, identityOf(block.code()));
                    }
                }
            }
            // 无论块体是否解码成功，一律跳到块末（块长含长度字段自身）
            int expected = blockStart + length;
            if (r.position() < expected) {
                r.skip(expected - r.position());
            }
            index++;
        }
    }


    private static long zoneKey(int seat, int location, int sequence) {
        return ((long) seat << 24) | ((long) location << 8) | (sequence & 0xFF);
    }

    private Card findAt(int seat, int location, int sequence) {
        Long id = zoneIndex.get(zoneKey(seat, location, sequence));
        return id == null ? null : cards.get(id);
    }

    /** 某槽位上卡实例的 id（供请求解码引用；无则 0）。 */
    public synchronized long instanceIdAt(int seat, int location, int sequence) {
        Long id = zoneIndex.get(zoneKey(seat, location, sequence));
        return id == null ? 0L : id;
    }

    /**
     * 某格上卡片**已知**的身份；拿不到就是 {@link CardIdentity#UNKNOWN}。
     */
    public synchronized CardIdentity identityAt(int seat, int location, int sequence) {
        Long id = zoneIndex.get(zoneKey(seat, location, sequence));
        if (id == null) {
            return CardIdentity.UNKNOWN;
        }
        Card card = cards.get(id);
        return card == null || card.identity == null ? CardIdentity.UNKNOWN : card.identity;
    }

    private Card takeFromZone(ZoneMapper.Packed from) {
        Long id = zoneIndex.remove(zoneKey(from.controller(), from.location(), from.sequence()));
        if (id == null) {
            return null;
        }
        Card card = cards.get(id);
        removeFromZoneLists(card);
        return card;
    }

    private void place(Card card) {
        if (card.id == 0) {
            card.id = nextInstanceId++;
            cards.put(card.id, card);
        }
        if (card.location == ZoneMapper.LOC_MZONE || card.location == ZoneMapper.LOC_SZONE
                || card.location == ZoneMapper.LOC_FZONE) {
            long key = zoneKey(card.controller, card.location, card.sequence);
            Long prev = zoneIndex.get(key);
            if (prev != null && prev != card.id) {
                // 槽位唯一性不变式：一格只能有一张卡。旧占用者若不撤出 cards，
                // 会与新区占用者同时出现在快照里，任何按实例计数的逻辑都会翻倍。
                Card stale = cards.remove(prev);
                if (stale != null) {
                    removeFromZoneLists(stale);
                }
            }
            zoneIndex.put(key, card.id);
        }
    }

    /**
     * 从手牌列表摘出即将移动的卡（手牌不占 zoneIndex，必须单独定位）。
     * <p>优先按卡号匹配（我方视角可见真实卡号）；卡号不可见时取首张未知卡；
     * 都匹配不上则退化为"摘首张"——宁可张数正确，也不要让手牌无限膨胀。
     */
    private Card takeFromHand(int seat, long code) {
        Player p = players.get(seat);
        if (p == null || p.hand.isEmpty()) {
            return null;
        }
        int idx = -1;
        for (int i = 0; i < p.hand.size(); i++) {
            Card c = cards.get(p.hand.get(i));
            if (c == null) {
                continue;
            }
            boolean match = code != 0
                    ? c.identity.passcode() == code
                    : c.identity.isUnknown();
            if (match) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            idx = 0;
        }
        return cards.get(p.hand.remove(idx));
    }

    private long register(Card card) {
        if (card.id == 0) {
            card.id = nextInstanceId++;
            cards.put(card.id, card);
        }
        return card.id;
    }

    private void clearZones(int seat) {
        zoneIndex.keySet().removeIf(key ->
                (key >>> 24) == seat
                        && (((key >>> 8) & 0xFF) == ZoneMapper.LOC_MZONE
                        || ((key >>> 8) & 0xFF) == ZoneMapper.LOC_SZONE
                        || ((key >>> 8) & 0xFF) == ZoneMapper.LOC_FZONE));
        cards.values().removeIf(c -> c.controller == seat &&
                (c.location == ZoneMapper.LOC_MZONE || c.location == ZoneMapper.LOC_SZONE
                        || c.location == ZoneMapper.LOC_FZONE));
    }

    private void removeFromZoneLists(Card card) {
        if (card == null) {
            return;
        }
        Player p = players.get(card.controller);
        if (p == null) {
            return;
        }
        switch (card.location) {
            case ZoneMapper.LOC_HAND -> p.hand.removeIf(id -> cards.get(id) == card);
            case ZoneMapper.LOC_GRAVE -> {
                int idx = indexOfIdentity(p.graveyard, card.identity);
                if (idx >= 0) p.graveyard.remove(idx);
            }
            case ZoneMapper.LOC_REMOVED -> {
                int idx = indexOfIdentity(p.banished, card.identity);
                if (idx >= 0) p.banished.remove(idx);
            }
            case ZoneMapper.LOC_EXTRA -> {
                int idx = indexOfIdentity(p.extraDeck, card.identity);
                if (idx >= 0) p.extraDeck.remove(idx);
            }
            default -> {
            }
        }
    }

    private void adjustZoneLists(ZoneMapper.Packed from, ZoneMapper.Packed to, Card card) {
        Player p = players.get(to.controller());
        if (p != null) {
            switch (to.location()) {
                case ZoneMapper.LOC_HAND -> p.hand.add(card.id);
                case ZoneMapper.LOC_GRAVE -> p.graveyard.add(card.identity);
                case ZoneMapper.LOC_REMOVED -> p.banished.add(card.identity);
                case ZoneMapper.LOC_EXTRA -> p.extraDeck.add(card.identity);
                default -> {
                }
            }
        }
        if (from != null) {
            Player fp = players.get(from.controller());
            if (fp != null) {
                switch (from.location()) {
                    case ZoneMapper.LOC_DECK -> fp.mainDeckCount = Math.max(0, fp.mainDeckCount - 1);
                    case ZoneMapper.LOC_EXTRA -> fp.extraDeckCount = Math.max(0, fp.extraDeckCount - 1);
                    default -> {
                    }
                }
            }
            switch (to.location()) {
                case ZoneMapper.LOC_DECK -> {
                    Player tp = players.get(to.controller());
                    if (tp != null) tp.mainDeckCount++;
                }
                case ZoneMapper.LOC_EXTRA -> {
                    Player tp = players.get(to.controller());
                    if (tp != null) tp.extraDeckCount++;
                }
                default -> {
                }
            }
        }
    }

    private static int indexOfIdentity(List<CardIdentity> list, CardIdentity identity) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).passcode() == identity.passcode()) {
                return i;
            }
        }
        return -1;
    }

    private CardIdentity identityOf(long code) {
        if (code == 0) {
            return CardIdentity.UNKNOWN;
        }
        return new CardIdentity.Known(code, resolver == null ? null
                : resolver.resolve(code).orElse(null));
    }

    private PlayerSide sideOf(int seat) {
        return seat == selfSeat ? PlayerSide.SELF : PlayerSide.OPPONENT;
    }

    private ZoneRef zoneRef(int controller, int location, int sequence) {
        ZoneRef zone = ZoneMapper.toZone(controller, location, sequence, sideOf(controller));
        return zone != null ? zone : ZoneRef.deck(sideOf(controller));
    }


    public synchronized DuelState snapshot() {
        Map<PlayerSide, PlayerState> playerStates = new LinkedHashMap<>();
        Map<Long, CardInstance> instanceMap = new LinkedHashMap<>();

        for (var e : cards.entrySet()) {
            Card c = e.getValue();
            instanceMap.put(e.getKey(), new CardInstance(
                    e.getKey(), c.identity,
                    zoneRef(c.controller, c.location, c.sequence),
                    ZoneMapper.toPosition(c.position),
                    Map.copyOf(c.counters), List.copyOf(c.xyzMaterials)));
        }

        for (var e : players.entrySet()) {
            int seat = e.getKey();
            Player p = e.getValue();
            List<CardInstance> hand = p.hand.stream()
                    .map(id -> instanceMap.getOrDefault(id,
                            new CardInstance(id, CardIdentity.UNKNOWN,
                                    ZoneRef.hand(sideOf(seat)), null, Map.of(), List.of())))
                    .toList();
            playerStates.put(sideOf(seat), new PlayerState(
                    sideOf(seat), seat, p.name, p.lp, hand,
                    p.mainDeckCount, p.extraDeckCount,
                    List.copyOf(p.graveyard), List.copyOf(p.banished), List.copyOf(p.extraDeck)));
        }

        return new DuelState(duelRule, turnNumber, sideOf(turnSeat), phase(),
                playerStates, instanceMap, List.copyOf(chain), result, selfSeat);
    }
}
