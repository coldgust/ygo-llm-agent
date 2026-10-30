package com.github.coldgust.ygoprosdk.core;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.decision.ChoiceSelection;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoprosdk.protocol.ByteWriter;
import java.util.List;

/**
 * 应答编码：领域 {@link ChoiceSelection} → CTOS_RESPONSE payload（规范 §8）。
 *
 * <p>编码完全基于"请求 + 选择"二元组：选择的各字段引用请求给出的候选对象，
 * 编码器用相等性求出候选下标 / 位图，不需要访问镜像。
 */
public final class ResponseEncoder {

    private ResponseEncoder() {
    }

    /**
     * 编码应答。
     *
     * @param request      待回答的请求
     * @param selection    决策结果（必须与请求类型匹配）
     * @param selfSeat     我方绝对座位号（区域应答的 player 字段需要）
     * @return CTOS_RESPONSE payload
     */
    public static byte[] encode(ChoiceRequest request, ChoiceSelection selection, int selfSeat) {
        if (selection instanceof ChoiceSelection.Cancel) {
            return encodeCancel(request);
        }
        return switch (request) {
            case ChoiceRequest.MainPhaseRequest r -> encodeMainPhase(r, (ChoiceSelection.MainPhaseSelection) selection);
            case ChoiceRequest.BattlePhaseRequest r -> encodeBattle(r, (ChoiceSelection.BattlePhaseSelection) selection);
            case ChoiceRequest.CardSelectRequest r -> encodeCardSelect(r, (ChoiceSelection.CardSelection) selection);
            case ChoiceRequest.TributeSelectRequest r -> encodeTribute(r, (ChoiceSelection.TributeSelection) selection);
            case ChoiceRequest.SumRequest r -> encodeSum(r, (ChoiceSelection.SumSelection) selection);
            case ChoiceRequest.UnselectRequest r -> encodeUnselect(r, (ChoiceSelection.UnselectSelection) selection);
            case ChoiceRequest.ChainRequest r -> encodeChain(r, (ChoiceSelection.ChainSelection) selection);
            case ChoiceRequest.PlaceRequest r -> encodePlace(r, (ChoiceSelection.PlaceSelection) selection, selfSeat);
            case ChoiceRequest.DisfieldRequest r -> encodePlace(r, (ChoiceSelection.PlaceSelection) selection, selfSeat);
            case ChoiceRequest.PositionRequest r -> encodePosition(r, (ChoiceSelection.PositionSelection) selection);
            case ChoiceRequest.EffectYesNoRequest r -> i32(((ChoiceSelection.EffectYesNoSelection) selection).activate() ? 1 : 0);
            case ChoiceRequest.YesNoRequest r -> i32(((ChoiceSelection.YesNoSelection) selection).yes() ? 1 : 0);
            case ChoiceRequest.OptionRequest r -> i32(((ChoiceSelection.OptionSelection) selection).index());
            case ChoiceRequest.DeclareRaceRequest r -> encodeRace(r, (ChoiceSelection.DeclareRaceSelection) selection);
            case ChoiceRequest.DeclareAttributeRequest r -> encodeAttribute(r, (ChoiceSelection.DeclareAttributeSelection) selection);
            case ChoiceRequest.DeclareCardRequest r -> i32((int) ((ChoiceSelection.DeclareCardSelection) selection).passcode());
            case ChoiceRequest.DeclareNumberRequest r -> i32(((ChoiceSelection.DeclareNumberSelection) selection).index());
            case ChoiceRequest.SortRequest r -> encodeSort(r, (ChoiceSelection.SortSelection) selection);
            case ChoiceRequest.CounterRequest r -> encodeCounter(r, (ChoiceSelection.CounterSelection) selection);
        };
    }

    /** 取消编码：统一 int32 -1；SORT_CARD 为首字节 0xFF。 */
    private static byte[] encodeCancel(ChoiceRequest request) {
        if (request instanceof ChoiceRequest.SortRequest) {
            return new byte[]{(byte) 0xFF};
        }
        return i32(-1);
    }

    /* ---------- 主要 / 战斗阶段 ---------- */

    private static byte[] encodeMainPhase(ChoiceRequest.MainPhaseRequest r, ChoiceSelection.MainPhaseSelection sel) {
        return switch (sel.action()) {
            case ChoiceSelection.Summon a -> i32(actionIndex(0, identityIndexOf(r.normalSummonable(), a.card())));
            case ChoiceSelection.SpecialSummon a -> i32(actionIndex(1, identityIndexOf(r.specialSummonable(), a.card())));
            case ChoiceSelection.Reposition a -> i32(actionIndex(2, identityIndexOf(r.repositionable(), a.card())));
            case ChoiceSelection.SetMonster a -> i32(actionIndex(3, identityIndexOf(r.monsterSettable(), a.card())));
            case ChoiceSelection.SetSpellTrap a -> i32(actionIndex(4, identityIndexOf(r.spellTrapSettable(), a.card())));
            case ChoiceSelection.Activate a -> i32(actionIndex(5, identityIndexOf(r.activatable(), a.effect())));
            case ChoiceSelection.EnterBattlePhase ignored -> i32(6);
            case ChoiceSelection.EndTurn ignored -> i32(7);
            case ChoiceSelection.ShuffleHand ignored -> i32(8);
        };
    }

    private static byte[] encodeBattle(ChoiceRequest.BattlePhaseRequest r, ChoiceSelection.BattlePhaseSelection sel) {
        return switch (sel.action()) {
            case ChoiceSelection.BattleActivate a -> i32(actionIndex(0, identityIndexOf(r.activatable(), a.effect())));
            case ChoiceSelection.Attack a -> i32(actionIndex(1, identityIndexOf(r.attackable(), a.target())));
            case ChoiceSelection.EnterMainPhase2 ignored -> i32(2);
            case ChoiceSelection.EndBattlePhase ignored -> i32(3);
        };
    }

    private static int actionIndex(int type, int index) {
        if (index < 0) {
            throw new IllegalArgumentException("决策引用了不在候选集合中的选项");
        }
        return type | (index << 16);
    }

    /* ---------- 选卡族 ---------- */

    private static byte[] encodeCardSelect(ChoiceRequest.CardSelectRequest r, ChoiceSelection.CardSelection sel) {
        ByteWriter w = new ByteWriter().u8(sel.selected().size());
        for (var card : sel.selected()) {
            w.u8(indexOf(r.candidates(), card));
        }
        return w.toByteArray();
    }

    private static byte[] encodeTribute(ChoiceRequest.TributeSelectRequest r, ChoiceSelection.TributeSelection sel) {
        ByteWriter w = new ByteWriter().u8(sel.tributed().size());
        for (var tribute : sel.tributed()) {
            w.u8(indexOfCards(r.candidates(), tribute));
        }
        return w.toByteArray();
    }

    private static int indexOfCards(List<ChoiceRequest.TributeChoice> candidates, ChoiceRequest.TributeChoice tribute) {
        try {
            return identityIndexOf(candidates, tribute);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("祭品不在候选集合中", e);
        }
    }

    private static byte[] encodeSum(ChoiceRequest.SumRequest r, ChoiceSelection.SumSelection sel) {
        // 应答 = [count][必选卡下标（按请求顺序回显）][可选卡下标]
        int mustCount = r.mustSelect().size();
        ByteWriter w = new ByteWriter().u8(sel.selected().size());
        for (int i = 0; i < sel.selected().size(); i++) {
            var card = sel.selected().get(i);
            if (i < mustCount) {
                w.u8(indexOf(r.mustSelect(), card));
            } else {
                w.u8(indexOf(r.optional(), card));
            }
        }
        return w.toByteArray();
    }

    private static byte[] encodeUnselect(ChoiceRequest.UnselectRequest r, ChoiceSelection.UnselectSelection sel) {
        if (sel.step() instanceof ChoiceSelection.UnselectStep.Finish) {
            return i32(-1);
        }
        if (sel.step() instanceof ChoiceSelection.UnselectStep.Select select) {
            return new ByteWriter().u8(1).u8(indexOf(r.selectable(), select.card())).toByteArray();
        }
        if (sel.step() instanceof ChoiceSelection.UnselectStep.Unselect unselect) {
            int idx = identityIndexOf(r.selected(), unselect.card());
            if (idx < 0) {
                throw new IllegalArgumentException("取消目标不在已选列表中");
            }
            return new ByteWriter().u8(1).u8(r.selectable().size() + idx).toByteArray();
        }
        throw new IllegalArgumentException("未知的选择步骤");
    }

    /* ---------- 连锁 ---------- */

    private static byte[] encodeChain(ChoiceRequest.ChainRequest r, ChoiceSelection.ChainSelection sel) {
        if (sel.answer() instanceof ChoiceSelection.ChainAnswer.Decline) {
            return i32(-1);
        }
        if (sel.answer() instanceof ChoiceSelection.ChainAnswer.Respond respond) {
            return i32(identityIndexOf(r.options(), respond.option()));
        }
        throw new IllegalArgumentException("未知的连锁应答");
    }

    /* ---------- 区域（§8.5：count × 3 字节，无前置计数）---------- */

    private static byte[] encodePlace(ChoiceRequest request, ChoiceSelection.PlaceSelection sel, int selfSeat) {
        int count = request instanceof ChoiceRequest.PlaceRequest p ? p.count()
                : ((ChoiceRequest.DisfieldRequest) request).count();
        // count=0 = 引擎按 1 个区域处理（实测：须回 1 个真实区域；{0,0,0} 占位会动作失败循环，见规范 §8.5）
        if (sel.zones().size() != count && !(count == 0 && sel.zones().size() == 1)) {
            throw new IllegalArgumentException("应答区域数 %d != 请求 count %d"
                    .formatted(sel.zones().size(), count));
        }
        ByteWriter w = new ByteWriter();
        for (ZoneRef zone : sel.zones()) {
            int absOwner = zone.owner() == PlayerSide.SELF
                    ? selfSeat : 1 - selfSeat;
            ZoneMapper.Slot slot = ZoneMapper.toSlot(zone);
            w.u8(absOwner).u8(slot.location()).u8(slot.sequence());
        }
        return w.toByteArray();
    }

    /* ---------- 表示形式 ---------- */

    private static byte[] encodePosition(ChoiceRequest.PositionRequest r, ChoiceSelection.PositionSelection sel) {
        int bit = ZoneMapper.fromPosition(sel.position());
        if (!r.positions().contains(sel.position())) {
            throw new IllegalArgumentException("表示形式不在候选集合中");
        }
        return i32(bit);
    }

    /* ---------- 宣言族 ---------- */

    private static byte[] encodeRace(ChoiceRequest.DeclareRaceRequest r, ChoiceSelection.DeclareRaceSelection sel) {
        int mask = 0;
        for (var race : sel.races()) {
            mask |= RequestDecoder.RaceBits.bitOf(race);
        }
        return i32(mask);
    }

    private static byte[] encodeAttribute(ChoiceRequest.DeclareAttributeRequest r, ChoiceSelection.DeclareAttributeSelection sel) {
        int mask = 0;
        for (var attribute : sel.attributes()) {
            mask |= RequestDecoder.AttributeBits.bitOf(attribute);
        }
        return i32(mask);
    }

    /* ---------- 排序与指示物 ---------- */

    private static byte[] encodeSort(ChoiceRequest.SortRequest r, ChoiceSelection.SortSelection sel) {
        ByteWriter w = new ByteWriter();
        for (var card : sel.ordered()) {
            w.u8(indexOf(r.cards(), card));
        }
        return w.toByteArray();
    }

    private static byte[] encodeCounter(ChoiceRequest.CounterRequest r, ChoiceSelection.CounterSelection sel) {
        ByteWriter w = new ByteWriter();
        for (var candidate : r.candidates()) {
            int count = 0;
            for (var entry : sel.removals().entrySet()) {
                if (entry.getKey() == candidate.card()) {
                    count = entry.getValue();
                    break;
                }
            }
            w.u16(count);
        }
        return w.toByteArray();
    }

    /* ---------- 助手 ---------- */

    /**
     * 按引用相等求候选下标。
     *
     * <p>必须用 {@code ==} 而非 {@code equals}：解码层产出的候选可能是"记录相等"
     * 的同名牌卡（如卡组里三张青眼白龙，instanceId/sequence 无法区分），
     * equals 会永远命中第一条，把 [0,1] 的选择编码成 [0,0] 而被判非法。
     * 决策契约（见 ChoiceSelection）要求引用请求候选列表中的对象，引用相等即正确语义。
     */
    private static int identityIndexOf(List<?> candidates, Object target) {
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i) == target) {
                return i;
            }
        }
        throw new IllegalArgumentException("决策引用了不在候选集合中的对象: " + target);
    }

    private static int indexOf(List<ChoiceRequest.CardChoice> candidates, ChoiceRequest.CardChoice card) {
        try {
            return identityIndexOf(candidates, card);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("决策引用了不在候选集合中的卡: " + card, e);
        }
    }

    private static byte[] i32(int v) {
        return new ByteWriter().i32(v).toByteArray();
    }
}
