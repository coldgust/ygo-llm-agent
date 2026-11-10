package com.github.coldgust.ygoprosdk.session;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.decision.ChoiceSelection;
import com.github.coldgust.ygodomain.duel.CardInstance;
import com.github.coldgust.ygodomain.duel.DuelResult;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoduellog.InMemoryDuelLog;
import com.github.coldgust.ygoduellog.DuelRecorder;
import com.github.coldgust.ygoduellog.event.DuelEventKind;
import com.github.coldgust.ygoduellog.event.DuelEventPayload;

import com.github.coldgust.ygoduellog.model.CandidateView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SDK → 对局记录器的翻译桥。
 *
 * <p>作为 {@link DuelListener} 注册在会话监听器列表**首位**：
 * <ul>
 *   <li>状态快照 diff → TURN_STARTED / PHASE_ENTERED / CARD_MOVED / LP_CHANGED；</li>
 *   <li>首个有效快照 → DUEL_STARTED（含双方玩家与卡组规模）；</li>
 *   <li>决策请求/应答 → DECISION_REQUESTED / DECISION_MADE（含 thinkMs、来源、RETRY 计数）；</li>
 *   <li>终局/断线 → DUEL_ENDED。</li>
 * </ul>
 *
 * <p>可见度限制：ygopro 协议中对手被问的请求不下发（§7），记录到的请求全部为
 * {@code Visibility.FULL} 的己方请求；对手决策只能以状态变化呈现。
 */
final class DuelLogBridge implements DuelListener {

    private final DuelRecorder log;
    private final String selfName;
    private final boolean decisionContextEnabled;

    private DuelState latest;
    private boolean startedEmitted;
    private int lastTurn = -1;
    private String lastPhase;
    private long turnStartMillis = System.currentTimeMillis();
    private volatile long requestArrivedMillis;
    private volatile long lastRequestedSeq;
    private boolean endedEmitted;

    DuelLogBridge(DuelRecorder log, String selfName, boolean decisionContextEnabled) {
        this.log = log;
        this.selfName = selfName;
        this.decisionContextEnabled = decisionContextEnabled;
    }


    @Override
    public void onStateUpdate(DuelState state) {
        latest = state;
        if (!startedEmitted && state.turnNumber() >= 1) {
            emitDuelStarted(state);
            startedEmitted = true;
        }
        if (!startedEmitted) {
            return;
        }
        if (state.turnNumber() != lastTurn) {
            lastTurn = state.turnNumber();
            turnStartMillis = System.currentTimeMillis();
            String player = "P" + absoluteSeat(state, state.turnPlayer());
            log.updateContext(lastTurn, state.phase().name(), turnStartMillis);
            log.append("SYSTEM", DuelEventKind.TURN_STARTED,
                    new DuelEventPayload.TurnStarted(player, lastTurn));
            snapshot(state); // EACH_TURN 快照
        }
        if (!state.phase().name().equals(lastPhase)) {
            lastPhase = state.phase().name();
            log.updateContext(lastTurn, lastPhase, turnStartMillis);
            log.append("SYSTEM", DuelEventKind.PHASE_ENTERED,
                    new DuelEventPayload.PhaseEntered(lastPhase));
        }
        diffState(state);
        prevSnapshot = state;
    }

    private void emitDuelStarted(DuelState state) {
        var self = state.player(PlayerSide.SELF);
        var opp = state.player(PlayerSide.OPPONENT);
        var players = List.of(
                new DuelEventPayload.PlayerInfo(self.seat(), selfName(),
                        self.mainDeckCount() + self.hand().size(), self.extraDeckCount()),
                new DuelEventPayload.PlayerInfo(opp.seat(), opp.name(),
                        opp.mainDeckCount() + opp.hand().size(), opp.extraDeckCount()));
        log.append("SYSTEM", DuelEventKind.DUEL_STARTED, new DuelEventPayload.DuelStarted(
                players, state.duelRule(), self.lifePoints(), self.hand().size(), 1, 0));
        if (log instanceof InMemoryDuelLog mem) {
            mem.markSelfSeat(state.selfSeat());
        }
    }

    private String selfName() {
        return selfName == null || selfName.isBlank() ? "self" : selfName;
    }

    private static int absoluteSeat(DuelState state, PlayerSide side) {
        return side == PlayerSide.SELF ? state.selfSeat() : 1 - state.selfSeat();
    }

    /** 相邻快照 diff → 卡移动 / LP 变动。 */
    private void diffState(DuelState state) {
        DuelState prev = prevSnapshot;
        if (prev == null) {
            return;
        }
        Map<Long, CardInstance> prevCards = prev.cards();
        Map<Long, CardInstance> nowCards = state.cards();
        for (CardInstance now : nowCards.values()) {
            CardInstance before = prevCards.get(now.instanceId());
            if (before == null) {
                log.append("SYSTEM", DuelEventKind.CARD_MOVED, new DuelEventPayload.CardMoved(
                        passcodeOf(now), identityOf(now),
                        null, null,
                        zoneOf(now), positionOf(now), null));
            } else if (!before.zone().equals(now.zone())
                    || !Objects.equals(before.position(), now.position())) {
                log.append("SYSTEM", DuelEventKind.CARD_MOVED, new DuelEventPayload.CardMoved(
                        passcodeOf(now), identityOf(now),
                        zoneOf(before), positionOf(before),
                        zoneOf(now), positionOf(now), null));
            }
        }
        for (CardInstance before : prevCards.values()) {
            if (!nowCards.containsKey(before.instanceId())) {
                log.append("SYSTEM", DuelEventKind.CARD_MOVED, new DuelEventPayload.CardMoved(
                        passcodeOf(before), identityOf(before),
                        zoneOf(before), positionOf(before),
                        null, null, null));
            }
        }
        lpDiff(prev, state, PlayerSide.SELF);
        lpDiff(prev, state, PlayerSide.OPPONENT);
    }

    private DuelState prevSnapshot;

    private void lpDiff(DuelState prev, DuelState now, PlayerSide side) {
        int before = prev.player(side).lifePoints();
        int after = now.player(side).lifePoints();
        if (before != after) {
            log.append("SYSTEM", DuelEventKind.LP_CHANGED, new DuelEventPayload.LpChanged(
                    "P" + absoluteSeat(now, side), after - before, after, null));
        }
    }

    private static long passcodeOf(CardInstance card) {
        return card.identity() == null || card.isUnknown() ? 0 : card.passcode();
    }

    private static String identityOf(CardInstance card) {
        return card.identity() == null || card.isUnknown() ? "unknown" : String.valueOf(card.passcode());
    }

    private static String zoneOf(CardInstance card) {
        var zone = card.zone();
        return (zone.owner() == PlayerSide.SELF ? "SELF" : "OPP") + ":" + zone.kind()
                + (zone.sequence() > 0 || zone.kind().name().contains("ZONE") ? "#" + zone.sequence() : "");
    }

    private static String positionOf(CardInstance card) {
        return card.position() == null ? null : card.position().name();
    }

    private void snapshot(DuelState state) {
        log.append("SYSTEM", DuelEventKind.STATE_SNAPSHOT, new DuelEventPayload.StateSnapshot(state));
    }


    @Override
    public void onChoiceAvailable(ChoiceRequest request) {
        if (!startedEmitted) {
            return;
        }
        requestArrivedMillis = System.currentTimeMillis();
        String actor = actorSelf();
        lastRequestedSeq = log.append(actor, DuelEventKind.DECISION_REQUESTED,
                new DuelEventPayload.DecisionRequested(actor, request.getClass().getSimpleName(),
                        DuelEventPayload.DecisionRequested.Visibility.FULL,
                        candidateCount(request), summarize(request)));
        if (decisionContextEnabled) {
            log.append(actor, DuelEventKind.DECISION_CONTEXT, buildContext(request));
        }
    }


    private static DuelEventPayload.DecisionContext buildContext(ChoiceRequest request) {
        List<DuelEventPayload.DecisionContext.CandidateView> views = new ArrayList<>();
        Integer min = null;
        Integer max = null;
        if (request instanceof ChoiceRequest.MainPhaseRequest m) {
            addCards(views, "normalSummon", m.normalSummonable());
            addCards(views, "specialSummon", m.specialSummonable());
            addCards(views, "reposition", m.repositionable());
            addCards(views, "setMonster", m.monsterSettable());
            addCards(views, "setSpellTrap", m.spellTrapSettable());
            addEffects(views, "activate", m.activatable());
        } else if (request instanceof ChoiceRequest.BattlePhaseRequest b) {
            addEffects(views, "battleActivate", b.activatable());
            b.attackable().forEach(a -> views.add(new DuelEventPayload.DecisionContext.CandidateView(
                    "attack", a.card().identity().passcode(), zoneOf(a.card().zone()), 0, a.directAttack())));
        } else if (request instanceof ChoiceRequest.ChainRequest c) {
            c.options().forEach(o -> views.add(new DuelEventPayload.DecisionContext.CandidateView(
                    o.forced() ? "chainForced" : "chain", o.effect().card().identity().passcode(),
                    zoneOf(o.effect().card().zone()), o.effect().description(), false)));
        } else if (request instanceof ChoiceRequest.CardSelectRequest s) {
            min = s.min();
            max = s.max();
            addCards(views, "card", s.candidates());
        } else if (request instanceof ChoiceRequest.TributeSelectRequest t) {
            min = t.min();
            max = t.max();
            t.candidates().forEach(c -> views.add(new DuelEventPayload.DecisionContext.CandidateView(
                    "tribute", c.card().identity().passcode(), zoneOf(c.card().zone()), c.releaseCount(), false)));
        } else if (request instanceof ChoiceRequest.SumRequest s) {
            min = s.min();
            max = s.max();
            addCards(views, "sumMust", s.mustSelect());
            addCards(views, "sumOptional", s.optional());
        } else if (request instanceof ChoiceRequest.UnselectRequest u) {
            min = u.min();
            max = u.max();
            addCards(views, "selectable", u.selectable());
            addCards(views, "selected", u.selected());
        } else if (request instanceof ChoiceRequest.PlaceRequest p) {
            p.availableZones().forEach(z -> views.add(new DuelEventPayload.DecisionContext.CandidateView(
                    "place", 0, zoneOf(z), p.count(), false)));
        } else if (request instanceof ChoiceRequest.DisfieldRequest d) {
            d.availableZones().forEach(z -> views.add(new DuelEventPayload.DecisionContext.CandidateView(
                    "disfield", 0, zoneOf(z), d.count(), false)));
        } else if (request instanceof ChoiceRequest.PositionRequest p) {
            p.positions().forEach(pos -> views.add(new DuelEventPayload.DecisionContext.CandidateView(
                    "position", p.card().identity().passcode(), zoneOf(p.card().zone()), 0, false)));
        } else if (request instanceof ChoiceRequest.OptionRequest o) {
            for (int i = 0; i < o.optionDescriptions().size(); i++) {
                views.add(new DuelEventPayload.DecisionContext.CandidateView(
                        "option", 0, null, o.optionDescriptions().get(i), false));
            }
        } else if (request instanceof ChoiceRequest.DeclareRaceRequest r) {
            r.available().forEach(race -> views.add(new DuelEventPayload.DecisionContext.CandidateView(
                    "race", 0, null, race.ordinal(), false)));
        } else if (request instanceof ChoiceRequest.DeclareAttributeRequest a) {
            a.available().forEach(attr -> views.add(new DuelEventPayload.DecisionContext.CandidateView(
                    "attribute", 0, null, attr.ordinal(), false)));
        } else if (request instanceof ChoiceRequest.DeclareCardRequest c) {
            c.opcodes().forEach(opcode -> views.add(new DuelEventPayload.DecisionContext.CandidateView(
                    "opcode", 0, null, opcode, false)));
        } else if (request instanceof ChoiceRequest.DeclareNumberRequest n) {
            n.numbers().forEach(number -> views.add(new DuelEventPayload.DecisionContext.CandidateView(
                    "number", 0, null, number, false)));
        } else if (request instanceof ChoiceRequest.SortRequest s) {
            addCards(views, "sort", s.cards());
        } else if (request instanceof ChoiceRequest.CounterRequest c) {
            c.candidates().forEach(x -> views.add(new DuelEventPayload.DecisionContext.CandidateView(
                    "counter", x.card().identity().passcode(), zoneOf(x.card().zone()), x.available(), false)));
        }
        return new DuelEventPayload.DecisionContext(request.getClass().getSimpleName(),
                DuelEventPayload.DecisionRequested.Visibility.FULL, views.size(), views, min, max,
                request.cancelable());
    }

    private static void addCards(List<DuelEventPayload.DecisionContext.CandidateView> views, String kind,
                                 List<ChoiceRequest.CardChoice> cards) {
        cards.forEach(c -> views.add(new DuelEventPayload.DecisionContext.CandidateView(
                kind, c.identity().passcode(), zoneOf(c.zone()), 0, false)));
    }

    private static void addEffects(List<DuelEventPayload.DecisionContext.CandidateView> views, String kind,
                                   List<ChoiceRequest.EffectChoice> effects) {
        effects.forEach(e -> views.add(new DuelEventPayload.DecisionContext.CandidateView(
                kind, e.card().identity().passcode(), zoneOf(e.card().zone()), e.description(), false)));
    }

    private static String zoneOf(ZoneRef zone) {
        return zone == null ? null : zone.owner() + ":" + zone.kind() + "#" + zone.sequence();
    }

    @Override
    public void onResponseSent(ChoiceRequest request, ChoiceSelection selection,
                               ResponseSource source, int retryCount) {
        if (!startedEmitted) {
            return;
        }
        long think = System.currentTimeMillis() - requestArrivedMillis;
        String actor = actorSelf();
        log.append(actor, DuelEventKind.DECISION_MADE, new DuelEventPayload.DecisionMade(
                lastRequestedSeq, actor, request.getClass().getSimpleName(),
                String.valueOf(selection), source.name(), think, retryCount));
        if (latest != null) {
            snapshot(latest); // EACH_DECISION 快照
        }
    }

    @Override
    public void onRetryIssued(ChoiceRequest request, int retryCount) {
        log.append(actorSelf(), DuelEventKind.RETRY_ISSUED, new DuelEventPayload.RetryIssued(
                actorSelf(), retryCount, request.getClass().getSimpleName()));
    }

    @Override
    public void onEffectActivated(int controllerSeat, long passcode, long description) {
        if (!startedEmitted) {
            return;
        }
        String player = "P" + controllerSeat;
        log.append(player, DuelEventKind.EFFECT_ACTIVATED,
                new DuelEventPayload.EffectActivated(player, passcode, description));
    }

    private String actorSelf() {
        return latest == null ? "P0" : "P" + latest.selfSeat();
    }


    @Override
    public void onDuelEnd(DuelResult result) {
        if (endedEmitted) {
            return;
        }
        endedEmitted = true;
        String winner = result.winner() == null || latest == null ? null
                : "P" + absoluteSeat(latest, result.winner());
        log.append("SYSTEM", DuelEventKind.DUEL_ENDED, new DuelEventPayload.DuelEnded(
                winner, result.reason().name(), latest == null ? lastTurn : latest.turnNumber()));
    }

    @Override
    public void onDisconnected() {
        if (endedEmitted) {
            return;
        }
        endedEmitted = true;
        log.append("SYSTEM", DuelEventKind.DUEL_ENDED, new DuelEventPayload.DuelEnded(
                null, "DISCONNECTED", lastTurn));
    }


    private static int candidateCount(ChoiceRequest request) {
        if (request instanceof ChoiceRequest.MainPhaseRequest m) {
            return m.normalSummonable().size() + m.specialSummonable().size() + m.repositionable().size()
                    + m.monsterSettable().size() + m.spellTrapSettable().size() + m.activatable().size()
                    + (m.canEnterBattlePhase() ? 1 : 0) + (m.canEndTurn() ? 1 : 0);
        }
        if (request instanceof ChoiceRequest.BattlePhaseRequest b) {
            return b.activatable().size() + b.attackable().size()
                    + (b.canEnterMainPhase2() ? 1 : 0) + (b.canEndBattlePhase() ? 1 : 0);
        }
        if (request instanceof ChoiceRequest.ChainRequest c) {
            return c.options().size();
        }
        if (request instanceof ChoiceRequest.EffectYesNoRequest) {
            return 1;
        }
        if (request instanceof ChoiceRequest.CardSelectRequest s) {
            return s.candidates().size();
        }
        if (request instanceof ChoiceRequest.TributeSelectRequest t) {
            return t.candidates().size();
        }
        if (request instanceof ChoiceRequest.SumRequest s) {
            return s.mustSelect().size() + s.optional().size();
        }
        if (request instanceof ChoiceRequest.UnselectRequest u) {
            return u.selectable().size() + u.selected().size();
        }
        if (request instanceof ChoiceRequest.PlaceRequest p) {
            return p.availableZones().size();
        }
        if (request instanceof ChoiceRequest.DisfieldRequest d) {
            return d.availableZones().size();
        }
        if (request instanceof ChoiceRequest.PositionRequest p) {
            return p.positions().size();
        }
        if (request instanceof ChoiceRequest.OptionRequest o) {
            return o.optionDescriptions().size();
        }
        if (request instanceof ChoiceRequest.DeclareRaceRequest r) {
            return r.available().size();
        }
        if (request instanceof ChoiceRequest.DeclareAttributeRequest a) {
            return a.available().size();
        }
        if (request instanceof ChoiceRequest.DeclareCardRequest c) {
            return c.opcodes().size();
        }
        if (request instanceof ChoiceRequest.DeclareNumberRequest n) {
            return n.numbers().size();
        }
        if (request instanceof ChoiceRequest.SortRequest s) {
            return s.cards().size();
        }
        if (request instanceof ChoiceRequest.CounterRequest c) {
            return c.candidates().size();
        }
        return 0;
    }

    private static String summarize(ChoiceRequest request) {
        if (request instanceof ChoiceRequest.MainPhaseRequest m) {
            return "召唤%d 特招%d 改形%d 盖怪%d 盖魔陷%d 发动%d | 进战阶=%s 结束=%s".formatted(
                    m.normalSummonable().size(), m.specialSummonable().size(), m.repositionable().size(),
                    m.monsterSettable().size(), m.spellTrapSettable().size(), m.activatable().size(),
                    m.canEnterBattlePhase(), m.canEndTurn());
        }
        if (request instanceof ChoiceRequest.BattlePhaseRequest b) {
            return "发动%d 攻击%d | 进M2=%s 结束=%s".formatted(
                    b.activatable().size(), b.attackable().size(),
                    b.canEnterMainPhase2(), b.canEndBattlePhase());
        }
        if (request instanceof ChoiceRequest.ChainRequest c) {
            return "连锁候选%d（强制%d）".formatted(c.options().size(),
                    c.options().stream().filter(ChoiceRequest.ChainRequest.ChainOption::forced).count());
        }
        if (request instanceof ChoiceRequest.CardSelectRequest s) {
            return "选%d–%d张 候选%d 可取消=%s".formatted(s.min(), s.max(), s.candidates().size(), s.cancelable());
        }
        if (request instanceof ChoiceRequest.TributeSelectRequest t) {
            return "选%d–%d个祭品 候选%d".formatted(t.min(), t.max(), t.candidates().size());
        }
        if (request instanceof ChoiceRequest.SumRequest s) {
            return "等级和=%d 必选%d 可选%d".formatted(s.targetSum(), s.mustSelect().size(), s.optional().size());
        }
        if (request instanceof ChoiceRequest.UnselectRequest u) {
            return "选卡/反悔 已选%d 可选%d".formatted(u.selected().size(), u.selectable().size());
        }
        if (request instanceof ChoiceRequest.PlaceRequest p) {
            return "选%d个区域 候选%d".formatted(p.count(), p.availableZones().size());
        }
        if (request instanceof ChoiceRequest.PositionRequest p) {
            return "选表示形式：" + p.positions();
        }
        if (request instanceof ChoiceRequest.EffectYesNoRequest e) {
            return "是否发动 " + e.card().identity();
        }
        if (request instanceof ChoiceRequest.YesNoRequest) {
            return "是/否";
        }
        if (request instanceof ChoiceRequest.OptionRequest o) {
            return "多选一（%d 项）".formatted(o.optionDescriptions().size());
        }
        return request.getClass().getSimpleName();
    }
}
