package com.github.coldgust.ygoagent.decision.reflex;

import com.github.coldgust.ygodomain.card.Attribute;
import com.github.coldgust.ygodomain.card.MonsterRace;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.Position;
import com.github.coldgust.ygoagent.decision.model.Candidate;
import com.github.coldgust.ygoagent.decision.model.CandidateId;
import com.github.coldgust.ygoagent.decision.model.ChoiceIntent;
import com.github.coldgust.ygoagent.decision.model.DecisionPoint;
import com.github.coldgust.ygoagent.decision.port.ReflexPolicy;
import com.github.coldgust.ygoagent.decision.quirks.RequestQuirks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * REFLEX = <b>零策略假设层</b>：只回答"无论玩家的战略如何、答案都相同"的请求；
 * 凡是有取舍的，一律返回 {@code empty}，交给 ②计划匹配 → ④one-shot → ⑤兜底。
 *
 * <h2>为什么连锁不能由 REFLEX 决定（本层最重要的一条）</h2>
 * 连锁是游戏王里信息量最大的决策点，旧实现"没有必发效果就一律弃权"直接把整条决策链短路了。规则上它至少涉及：
 * <ul>
 *   <li><b>咒文速度</b>：只能连锁速度不低于自己的效果（反击陷阱 3 &gt; 速攻/通常陷阱/诱发即时 2 &gt; 通常魔法/诱发 1），
 *       所以同一时点的候选集合本身就带着"能不能接"的约束；</li>
 *   <li><b>优先权</b>：一方发动后优先权转移给对方，对方放弃才回到原发动者。谁先答、能不能再叠一层都取决于此；</li>
 *   <li><b>连锁顺序</b>：结算逆算（后发先至）。同一时点复数诱发效果还要按
 *       「必发 &gt; 公开场所的选发 &gt; 手牌/盖放的选发」排，同优先级由回合玩家先排、同一玩家可自排；</li>
 *   <li><b>资源与时机</b>：对手回合的每个连锁窗口要不要投 灰流丽/无限泡影/指名者，取决于手里的牌与本回合计划——
 *       这正是规划器该回答的问题，REFLEX 用一条"弃权"规则替它答等于剥夺了决策权。</li>
 * </ul>
 * 因此 CHAIN 只有三种退化情形可以直接答：无候选（只能弃权）、唯一选项且必发（只能发动）、
 * 其余一律让行。
 *
 * <h2>其余请求的划线</h2>
 * <ul>
 *   <li><b>让行</b>（有取舍）：{@code EFFECT_YES_NO}（是否发动某效果）、{@code POSITION}（攻/守影响战损）、
 *       {@code CARD_SELECT}/{@code TRIBUTE_SELECT}/{@code SUM}/{@code UNSELECT}（选哪些卡）、
 *       {@code OPTION}、{@code SORT}——除"必须全选/唯一合法/无可选"外都让行。</li>
 *   <li><b>保留确定性</b>：{@code PLACE}（位置由模型的计划/one-shot 一并给出，兑现不了就取第一个可放置，
 *       不为它单独发一次请求）、{@code DISFIELD}（禁对方区域）、{@code COUNTER}（移除计数点无差别）、
 *       {@code DECLARE_*}（协议只给 opcodes/可用集合，没有可读文本，
 *       让行也不能带来更好的答案）、{@code YES_NO}（只给一个 description 码，同上）。</li>
 * </ul>
 */
public final class ReflexPolicyImpl implements ReflexPolicy {

    @Override
    public Optional<ChoiceIntent> tryAnswer(DecisionPoint point) {
        return switch (point.kind()) {
            case MAIN_PHASE, BATTLE_PHASE -> Optional.empty(); // 战略层，永远让行
            case CHAIN -> chain(point);
            case EFFECT_YES_NO -> Optional.empty();            // "是否发动"是资源与时机的判断
            case YES_NO -> Optional.of(new ChoiceIntent.YesNo(true)); // 只给 description 码，无文本可读
            case POSITION -> singlePosition(point);
            case PLACE, DISFIELD -> place(point);
            case CARD_SELECT -> cardSelect(point);
            case TRIBUTE_SELECT -> tribute(point);
            case SUM -> sum(point);
            case UNSELECT -> unselect(point);
            case OPTION -> option(point);
            case DECLARE_RACE -> declareRace(point);
            case DECLARE_ATTRIBUTE -> declareAttribute(point);
            case DECLARE_CARD -> Optional.of(new ChoiceIntent.DeclareCard(14558127L));
            case DECLARE_NUMBER -> declareNumber(point);
            case SORT -> sort(point);
            case COUNTER -> counter(point);
        };
    }

    /* ---------- 连锁 ---------- */

    private Optional<ChoiceIntent> chain(DecisionPoint point) {
        var request = (ChoiceRequest.ChainRequest) point.request();
        List<Candidate.EffectC> effects = point.candidatesOf(Candidate.EffectC.class);

        // ① 无候选：弃权是唯一合法动作（最常见的窗口，确定性，可放心直答）
        if (request.options().isEmpty()) {
            return Optional.of(new ChoiceIntent.ChainDecline());
        }
        // ② 全是可选：值不值得投入资源 → 让行（这正是旧实现最大的问题）
        if (!request.hasForcedEffect()) {
            return Optional.empty();
        }
        // ③ 有必发且只有唯一选项：只能发动它
        if (request.options().size() == 1 && !effects.isEmpty()) {
            return Optional.of(new ChoiceIntent.ChainRespond(effects.getFirst().id()));
        }
        // ④ 有必发但存在多个选项：还要决定是否在必发之外再叠自己的效果（自排/资源）→ 让行
        return Optional.empty();
    }

    /* ---------- 表示形式 ---------- */

    private Optional<ChoiceIntent> singlePosition(DecisionPoint point) {
        var request = (ChoiceRequest.PositionRequest) point.request();
        if (request.positions().size() != 1) {
            return Optional.empty(); // 攻/守是策略：攻击表示给对手战伤机会，守备表示放弃输出
        }
        Position only = request.positions().iterator().next();
        return Optional.of(new ChoiceIntent.PositionChoice(only));
    }

    /* ---------- 区域 ---------- */

    /**
     * 放置区域：<b>确定性取前 N 个</b>。
     *
     * <p>位置由模型在<b>选动作的那一次</b>一并给出（计划步的 {@code zone}，或 one-shot 的 {@code zone}），
     * 执行器在那之后的位置请求里直接兑现；兑现不了才落到这里取"第一个可放置区域"。
     * 所以这里不该、也不会再为位置单独发一次 LLM 请求——位置请求紧跟在召唤/盖放之后，
     * 单独问一次等于每次落场都多一次往返，而多数格子在战术上确实无差别。
     */
    private Optional<ChoiceIntent> place(DecisionPoint point) {
        int count = point.request() instanceof ChoiceRequest.PlaceRequest p
                ? p.count()
                : ((ChoiceRequest.DisfieldRequest) point.request()).count();
        List<Candidate.ZoneC> zones = point.candidatesOf(Candidate.ZoneC.class);
        if (zones.isEmpty()) {
            return Optional.empty();
        }
        int take = RequestQuirks.placeZoneCount(count, zones.size());
        List<CandidateId> ids = zones.stream().limit(take).map(Candidate.ZoneC::id).toList();
        return Optional.of(new ChoiceIntent.Place(ids));
    }

    /* ---------- 选卡 ---------- */

    private Optional<ChoiceIntent> cardSelect(DecisionPoint point) {
        var request = (ChoiceRequest.CardSelectRequest) point.request();
        List<Candidate.CardC> cards = point.candidatesOf(Candidate.CardC.class);
        if (request.max() <= 0) {
            return Optional.of(new ChoiceIntent.Cancel()); // 只能不选
        }
        if (request.min() == request.max() && request.min() >= cards.size()) {
            return Optional.of(new ChoiceIntent.Select(all(cards))); // 必须全选，没有取舍
        }
        return Optional.empty(); // 选哪张是策略（送墓/除外/检索的取舍）
    }

    private Optional<ChoiceIntent> tribute(DecisionPoint point) {
        var request = (ChoiceRequest.TributeSelectRequest) point.request();
        List<Candidate.TributeC> cards = point.candidatesOf(Candidate.TributeC.class);
        if (request.min() == request.max() && request.min() >= cards.size()) {
            return Optional.of(new ChoiceIntent.Tribute(
                    cards.stream().map(Candidate.TributeC::id).toList()));
        }
        return Optional.empty(); // 祭品取舍（解放哪只怪兽）是策略
    }

    private Optional<ChoiceIntent> sum(DecisionPoint point) {
        var request = (ChoiceRequest.SumRequest) point.request();
        List<Candidate.CardC> all = point.candidatesOf(Candidate.CardC.class);
        // 只有"必然全部"（没有可选卡，必选卡已够）时才是唯一解；凑和组合本身是策略
        if (request.optional().isEmpty() && request.mustSelect().size() == all.size()) {
            return Optional.of(new ChoiceIntent.Sum(all(all)));
        }
        return Optional.empty();
    }

    private Optional<ChoiceIntent> unselect(DecisionPoint point) {
        var request = (ChoiceRequest.UnselectRequest) point.request();
        List<Candidate.CardC> selectable = point.candidatesOf(Candidate.CardC.class).stream()
                .filter(c -> !request.selected().contains(c.value()))
                .toList();
        if (selectable.isEmpty()) {
            // 没有可再选的卡：能结束就结束，否则只能取消
            return Optional.of(request.finishable()
                    ? new ChoiceIntent.UnselectFinish() : new ChoiceIntent.Cancel());
        }
        return Optional.empty();
    }

    /* ---------- 选项 ---------- */

    private Optional<ChoiceIntent> option(DecisionPoint point) {
        // 选项只有"描述码"，没有可读文本：让行也不能让模型判断得更好，保留确定性
        return Optional.of(new ChoiceIntent.Option(0));
    }

    /* ---------- 排序 ---------- */

    private Optional<ChoiceIntent> sort(DecisionPoint point) {
        List<Candidate.CardC> cards = point.candidatesOf(Candidate.CardC.class);
        if (cards.size() > 1) {
            return Optional.empty(); // 放回卡组顶的顺序影响后续抽卡，是策略
        }
        return Optional.of(new ChoiceIntent.Sort(cards.stream().map(Candidate.CardC::id).toList()));
    }

    /* ---------- 宣言类（协议只给编码/opcodes，没有可读文本） ---------- */

    private Optional<ChoiceIntent> declareRace(DecisionPoint point) {
        var request = (ChoiceRequest.DeclareRaceRequest) point.request();
        Set<MonsterRace> picked = new LinkedHashSet<>();
        for (MonsterRace race : request.available()) {
            if (picked.size() >= request.count()) {
                break;
            }
            picked.add(race);
        }
        return Optional.of(new ChoiceIntent.DeclareRace(picked));
    }

    private Optional<ChoiceIntent> declareAttribute(DecisionPoint point) {
        var request = (ChoiceRequest.DeclareAttributeRequest) point.request();
        Set<Attribute> picked = new LinkedHashSet<>();
        for (Attribute attr : request.available()) {
            if (picked.size() >= request.count()) {
                break;
            }
            picked.add(attr);
        }
        return Optional.of(new ChoiceIntent.DeclareAttribute(picked));
    }

    private Optional<ChoiceIntent> declareNumber(DecisionPoint point) {
        return Optional.of(new ChoiceIntent.DeclareNumber(0));
    }

    /* ---------- 计数点（结构操作，无差别） ---------- */

    private Optional<ChoiceIntent> counter(DecisionPoint point) {
        var request = (ChoiceRequest.CounterRequest) point.request();
        Map<CandidateId, Integer> removals = new LinkedHashMap<>();
        int remaining = request.total();
        for (Candidate.CounterC c : point.candidatesOf(Candidate.CounterC.class)) {
            if (remaining <= 0) {
                break;
            }
            int take = Math.min(remaining, Math.max(c.value().available(), 0));
            if (take > 0) {
                removals.put(c.id(), take);
                remaining -= take;
            }
        }
        return Optional.of(new ChoiceIntent.Counter(removals));
    }

    private static List<CandidateId> all(List<Candidate.CardC> cards) {
        return cards.stream().map(Candidate.CardC::id).toList();
    }
}
