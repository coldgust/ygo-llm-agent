package com.github.coldgust.ygoplayserver.duel.summary;

import com.github.coldgust.ygoduellog.model.DecisionEpisode;
import com.github.coldgust.ygoduellog.model.DuelRecord;
import com.github.coldgust.ygoplay.DuelRunResult;
import com.github.coldgust.ygoplay.DuelRunSpec;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DuelNarrative {

    private static final int ACTIONS_PER_TURN = 12;

    private final StringBuilder facts = new StringBuilder();
    private final StringBuilder timeline = new StringBuilder();

    private DuelNarrative() {
    }

    public static DuelNarrative build(String duelId, DuelRunSpec spec, DuelRunResult result,
                                      DuelRecord record, DuelTraceReader.TraceView trace,
                                      int maxTimeline) {
        DuelNarrative narrative = new DuelNarrative();
        narrative.appendFacts(duelId, spec, result, record);
        narrative.appendTimeline(record, trace, maxTimeline <= 0 ? 8_000 : maxTimeline);
        return narrative;
    }

    public String facts() {
        return facts.toString().trim();
    }

    public String timeline() {
        return timeline.toString().trim();
    }

    private void appendFacts(String duelId, DuelRunSpec spec, DuelRunResult result, DuelRecord record) {
        line("duel", duelId);
        line("日期", LocalDate.now().toString());
        line("我方卡组主题", spec.archetype() == null || spec.archetype().isBlank()
                ? "未指定" : spec.archetype());
        line("先后手", "first".equalsIgnoreCase(spec.move()) ? "先手" : "后手");
        if (result != null && result.duelResult() != null) {
            line("结果", result.duelResult().winner() + " 胜（" + result.duelResult().reason() + "）");
        } else {
            line("结果", "未知（对局未正常结束）");
        }
        if (record != null && record.meta() != null) {
            line("回合数", String.valueOf(record.meta().turns()));
        }
        if (result != null) {
            line("我方应答", result.stats().answered() + " 次；其中兜底 " + result.stats().fallbacks() + " 次");
        }
        if (spec.deckStrategy() != null && !spec.deckStrategy().isBlank()) {
            line("卡组思路（用户手写）", spec.deckStrategy().trim());
        }
    }

    private void appendTimeline(DuelRecord record, DuelTraceReader.TraceView trace, int limit) {
        Map<Integer, List<DecisionEpisode>> episodesByTurn = new LinkedHashMap<>();
        if (record != null) {
            for (DecisionEpisode episode : record.episodes()) {
                episodesByTurn.computeIfAbsent(episode.turn(), k -> new ArrayList<>()).add(episode);
            }
        }

        // 轨迹里的回合集合 ∪ 日志里的回合集合：任一侧缺失都要能出内容
        Map<Integer, List<DuelTraceReader.Action>> actionsByTurn = new LinkedHashMap<>();
        for (DuelTraceReader.Action action : trace.actions()) {
            actionsByTurn.computeIfAbsent(action.turn(), k -> new ArrayList<>()).add(action);
        }
        List<Integer> turns = new ArrayList<>();
        for (Integer turn : episodesByTurn.keySet()) {
            turns.add(turn);
        }
        for (Integer turn : actionsByTurn.keySet()) {
            if (!turns.contains(turn)) {
                turns.add(turn);
            }
        }
        turns.sort(Integer::compareTo);

        for (Integer turn : turns) {
            List<DuelTraceReader.Action> actions = actionsByTurn.getOrDefault(turn, List.of());
            appendTurn(timeline, turn, episodesByTurn.get(turn), actions,
                    trace.planFor(turn), ownershipOf(actions));
            if (timeline.length() > limit) {
                timeline.append("\n…（记录过长，后续回合已省略）\n");
                return;
            }
        }
        if (turns.isEmpty()) {
            timeline.append("（没有可用的逐回合记录：轨迹与对局日志都为空）\n");
        }
    }

    private void appendTurn(StringBuilder sb, int turn, List<DecisionEpisode> episodes,
                            List<DuelTraceReader.Action> actions,
                            DuelTraceReader.TurnPlanView plan, String ownership) {
        sb.append("### T").append(turn);
        if (!ownership.isEmpty()) {
            sb.append("（").append(ownership).append("）");
        }
        String lp = lpLine(episodes);
        if (!lp.isEmpty()) {
            sb.append(' ').append(lp);
        }
        sb.append('\n');

        if (plan != null) {
            if (!plan.goal().isBlank()) {
                sb.append("计划目标：").append(clip(plan.goal(), 200)).append('\n');
            }
            if (!plan.thinking().isBlank()) {
                sb.append("计划思路：").append(clip(plan.thinking(), 300)).append('\n');
            }
        }
        if (actions.isEmpty()) {
            sb.append("动作：（本回合我方没有可记录的动作）\n\n");
            return;
        }
        sb.append("动作：\n");
        int written = 0;
        int index = 0;
        while (index < actions.size() && written < ACTIONS_PER_TURN) {
            DuelTraceReader.Action action = actions.get(index);
            int repeat = 1;
            // 连续相同（请求 + 内容 + 来源）折叠成一条，否则一串"连锁响应：不响应"会淹没真正有信息量的动作
            while (index + repeat < actions.size() && same(actions.get(index + repeat), action)) {
                repeat++;
            }
            sb.append("- [").append(action.level().isBlank() ? "?" : action.level()).append("] ")
                    .append(action.request()).append("：").append(action.submitted());
            if (repeat > 1) {
                sb.append("（连续 ").append(repeat).append(" 次相同）");
            }
            if (action.fallback()) {
                sb.append("（兜底）");
            }
            if (!action.rationale().isBlank()) {
                sb.append(" ｜ 理由：").append(clip(action.rationale(), 160));
            }
            sb.append('\n');
            written++;
            index += repeat;
        }
        if (index < actions.size()) {
            sb.append("- （另有 ").append(actions.size() - index).append(" 次动作未列出）\n");
        }
        sb.append('\n');
    }

    private static boolean same(DuelTraceReader.Action a, DuelTraceReader.Action b) {
        return a.request().equals(b.request())
                && a.submitted().equals(b.submitted())
                && a.level().equals(b.level());
    }

    private static String lpLine(List<DecisionEpisode> episodes) {
        if (episodes == null || episodes.isEmpty()) {
            return "";
        }
        for (DecisionEpisode episode : episodes) {
            if (episode.selfLp() > 0 || episode.oppLp() > 0) {
                String hand = episode.handPasscodes().isEmpty()
                        ? "" : "，我方手牌 " + episode.handPasscodes().size() + " 张";
                return "（我方 LP " + episode.selfLp() + " / 对手 LP " + episode.oppLp() + hand + "）";
            }
        }
        return "";
    }

    private static String ownershipOf(List<DuelTraceReader.Action> actions) {
        boolean hasMainPhase = false;
        boolean hasAny = false;
        for (DuelTraceReader.Action action : actions) {
            hasAny = true;
            String request = action.request() == null ? "" : action.request();
            if (request.contains("主要") || request.contains("战斗")) {
                hasMainPhase = true;
                break;
            }
        }
        if (!hasAny) {
            return "";
        }
        return hasMainPhase ? "我方回合" : "对手回合";
    }

    private void line(String key, String value) {
        facts.append(key).append(": ").append(value).append('\n');
    }

    private static String clip(String text, int limit) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }
}
