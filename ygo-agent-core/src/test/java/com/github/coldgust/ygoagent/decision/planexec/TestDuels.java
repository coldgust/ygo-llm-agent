package com.github.coldgust.ygoagent.decision.planexec;

import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.duel.CardInstance;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.Phase;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.duel.PlayerState;
import com.github.coldgust.ygodomain.duel.Position;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoagent.decision.model.Candidate;
import com.github.coldgust.ygoagent.decision.model.Candidates;
import com.github.coldgust.ygoagent.decision.model.DecisionPoint;
import com.github.coldgust.ygoagent.decision.model.RequestKind;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 合成对局夹具：手工构造 DuelState / DecisionPoint（无 IO）。 */
public final class TestDuels {

    public static final long ENGAGE = 25048511L;   // 交闪（示意）
    public static final long HORNET = 75982077L;   // 大黄蜂（示意）
    public static final long WIDOW_ANCHOR = 98266377L;
    public static final long KAGARI = 63288574L;       // 闪刀姬-燎里
    /** 王之仆人-黑魔术师：多效果卡，③ 才是"对方场上魔陷全部破坏"（回归用）。 */
    public static final long SERVANT = 88570004L;
    /** 超魔导龙骑士：①『不会被效果破坏，不能作为效果的对象』（选目标时必排除的那种卡）。 */
    public static final long DRAGOON = 37818795L;
    /** 闪刀姬-零衣：① 解放自身从额外特召；② 墓地起跳（两条款都需要"发动"，用来区分渲染哪一条）。 */
    public static final long RAY = 26077387L;

    /** 内存版卡牌事实（测试用）。 */
    public static CardFacts facts() {
        Map<Long, CardFact> map = new HashMap<>();
        map.put(ENGAGE, new CardFact(ENGAGE, "闪刀起动-交闪", "Sky Striker Mecha - Hornet Drones",
                "SPELL", "通常魔法", "", "", null, null, null, "①：从卡组把「闪刀起动物」加入手卡。"));
        map.put(HORNET, new CardFact(HORNET, "大黄蜂浮游单元", "Hornet Drone", "MONSTER",
                "效果怪兽", "机械", "火", 1500, 1500, 4, "①：特殊召唤衍生物。"));
        map.put(WIDOW_ANCHOR, new CardFact(WIDOW_ANCHOR, "闪刀机-黑寡妇抓锚", "Widow Anchor",
                "SPELL", "通常魔法", "", "", null, null, null, "①：取对方怪兽的控制权要素。"));
        map.put(KAGARI, new CardFact(KAGARI, "闪刀姬-燎里", "Sky Striker Ace - Kagari", "MONSTER",
                "连接怪兽", "机械", "火", 1500, null, null,
                "①：这张卡特殊召唤成功的场合，以自己墓地1张「闪刀」魔法卡为对象才能发动。那张卡加入手卡。"));
        map.put(SERVANT, new CardFact(SERVANT, "王之仆人-黑魔术师", "Dark Magician the Servant",
                "MONSTER", "效果怪兽", "魔法师", "暗", 2500, 2500, 7,
                "①：这张卡的卡名只要在场上·墓地存在当作「黑魔术师」使用。"
                        + "②：这张卡在手卡存在的场合，把手卡1张魔法卡给对方观看才能发动。这张卡特殊召唤。"
                        + "那之后，可以从卡组把有「黑魔术师」的卡名记述的1张魔法·陷阱卡在自己场上盖放。"
                        + "③：自己·对方回合，从手卡丢弃1张魔法卡才能发动。对方场上的魔法·陷阱卡全部破坏。"));
        map.put(RAY, new CardFact(RAY, "闪刀姬-零衣", "Sky Striker Ace - Ray", "MONSTER",
                "效果／怪兽", "战士", "暗", 1500, 1500, 4,
                "①：自己·对方回合，把这张卡解放才能发动。从额外卡组把1只「闪刀姬」怪兽在额外怪兽区域特殊召唤。"
                        + "②：这张卡在墓地存在的状态，自己场上的「闪刀姬」连接怪兽因对方的效果从场上离开的场合"
                        + "或者被战斗破坏的场合才能发动。这张卡特殊召唤。"));
        map.put(DRAGOON, new CardFact(DRAGOON, "超魔导龙骑士-真红眼龙骑士", "Red-Eyes Dark Dragoon",
                "MONSTER", "融合／效果／怪兽", "魔法师", "暗", 3000, 2500, 8,
                "①：这张卡不会被效果破坏，双方不能把这张卡作为效果的对象。"
                        + "②：自己主要阶段才能发动。对方场上1只怪兽破坏，给与对方那个原本攻击力数值的伤害。"
                        + "③：1回合1次，卡的效果发动时，丢弃1张手卡才能发动。那个发动无效并破坏，这张卡的攻击力上升1000。"));
        map.put(44405066L, new CardFact(44405066L, "圣夜降临的骑士", "Knight", "MONSTER",
                "效果怪兽", "战士", "光", 2500, 2000, 7, "测试怪 A。"));
        map.put(70902743L, new CardFact(70902743L, "红莲魔龙", "Red Dragon", "MONSTER",
                "效果怪兽", "龙", "暗", 3000, 2000, 8, "测试怪 B。"));
        map.put(112240363L, new CardFact(112240363L, "巨盾守卫", "Big Shield", "MONSTER",
                "效果怪兽", "岩石", "地", 1000, 3000, 4, "高守备测试怪。"));
        return new CardFacts() {
            @Override
            public Optional<CardFact> byPasscode(long passcode) {
                return Optional.ofNullable(map.get(passcode));
            }

            @Override
            public long knownCount() {
                return map.size();
            }
        };
    }

    public static CardFact fact(long passcode) {
        return facts().byPasscode(passcode).orElseThrow();
    }

    public static CardIdentity known(long passcode) {
        return CardIdentity.Known.of(passcode);
    }

    public static CardInstance instance(long id, long passcode, ZoneRef zone) {
        return new CardInstance(id, known(passcode), zone, null, Map.of(), List.of());
    }

    public static DuelState state(int turn, boolean myTurn,
                                  List<CardInstance> selfMonsters,
                                  List<CardInstance> selfBackrow,
                                  List<CardInstance> oppMonsters,
                                  List<CardInstance> oppBackrow,
                                  int selfLp, int opponentLp) {
        PlayerState self = new PlayerState(PlayerSide.SELF, 0, "me", selfLp,
                List.of(instance(900, ENGAGE, ZoneRef.hand(PlayerSide.SELF))),
                30, 10, List.of(), List.of(), List.of());
        PlayerState opponent = new PlayerState(PlayerSide.OPPONENT, 1, "opp", opponentLp,
                List.of(
                        instance(950, CardIdentity.UNKNOWN, ZoneRef.hand(PlayerSide.OPPONENT)),
                        instance(951, CardIdentity.UNKNOWN, ZoneRef.hand(PlayerSide.OPPONENT)),
                        instance(952, CardIdentity.UNKNOWN, ZoneRef.hand(PlayerSide.OPPONENT))),
                25, 12, List.of(), List.of(), List.of());
        Map<Long, CardInstance> cards = new HashMap<>();
        selfMonsters.forEach(c -> cards.put(c.instanceId(), c));
        selfBackrow.forEach(c -> cards.put(c.instanceId(), c));
        oppMonsters.forEach(c -> cards.put(c.instanceId(), c));
        oppBackrow.forEach(c -> cards.put(c.instanceId(), c));
        return new DuelState(5, turn, myTurn ? PlayerSide.SELF : PlayerSide.OPPONENT,
                Phase.MAIN_PHASE_1,
                Map.of(PlayerSide.SELF, self, PlayerSide.OPPONENT, opponent),
                cards, List.of(), null, 0);
    }

    public static CardInstance instance(long id, long passcode, ZoneRef zone, Position position) {
        return new CardInstance(id, known(passcode), zone, position, Map.of(), List.of());
    }

    public static CardInstance instance(long id, CardIdentity identity, ZoneRef zone) {
        return new CardInstance(id, identity, zone, null, Map.of(), List.of());
    }

    /** 主阶段请求 + 决策点。 */
    public static DecisionPoint mainPhasePoint(DuelState state, List<ChoiceRequest.CardChoice> summonable,
                                               List<ChoiceRequest.EffectChoice> activatable) {
        ChoiceRequest request = new ChoiceRequest.MainPhaseRequest(
                summonable, List.of(), List.of(), List.of(), List.of(),
                activatable, true, true, false);
        return new DecisionPoint(1, state.turnNumber(), state.phase(), PlayerSide.SELF,
                request, state, Candidates.from(request), "state-hash", "request-hash",
                RequestKind.MAIN_PHASE);
    }

    public static ChoiceRequest.CardChoice cardChoice(long instanceId, long passcode) {
        return new ChoiceRequest.CardChoice(instanceId, known(passcode),
                ZoneRef.hand(PlayerSide.SELF));
    }

    public static ChoiceRequest.EffectChoice effectChoice(long instanceId, long passcode, long desc) {
        return new ChoiceRequest.EffectChoice(cardChoice(instanceId, passcode), desc);
    }

    /** 场上（魔陷区）的卡作为连锁候选：手上响应不按计划接地，场上响应才接地。 */
    public static ChoiceRequest.EffectChoice fieldEffectChoice(long instanceId, long passcode, long desc) {
        return new ChoiceRequest.EffectChoice(new ChoiceRequest.CardChoice(instanceId, known(passcode),
                ZoneRef.spellTrap(PlayerSide.SELF, 0)), desc);
    }

    public static Optional<Candidate> byPasscode(DecisionPoint point, long passcode) {
        return point.candidates().stream()
                .filter(c -> PlanGrounderPasscode.passcode(c) == passcode)
                .findFirst();
    }

    /** 避免测试直接依赖 PlanGrounder 的静态工具。 */
    private static final class PlanGrounderPasscode {
        static long passcode(Candidate candidate) {
            return switch (candidate) {
                case Candidate.CardC c -> c.value().identity().passcode();
                case Candidate.EffectC c -> c.value().card().identity().passcode();
                case Candidate.AttackC c -> c.value().card().identity().passcode();
                case Candidate.TributeC c -> c.value().card().identity().passcode();
                case Candidate.CounterC c -> c.value().card().identity().passcode();
                case Candidate.ZoneC ignored -> 0L;
                case Candidate.FlagC ignored -> 0L;
                case Candidate.DeclineC ignored -> 0L;
            };
        }
    }

    private TestDuels() {
    }
}
