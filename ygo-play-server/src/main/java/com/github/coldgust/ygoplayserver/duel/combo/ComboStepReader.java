package com.github.coldgust.ygoplayserver.duel.combo;

import com.github.coldgust.ygoagent.decision.planexec.CardFact;
import com.github.coldgust.ygoagent.decision.planexec.CardFacts;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * combo 录入的素材提取：从对局日志（JSONL）里切出用户回合的逐步操作。
 *
 * <p>用户先手（agent 强制后手）打完 combo、AI 回合自动认输，所以日志里
 * {@code turn == 1} 的全部状态事件就是这次录入：卡牌移动、阶段切换、效果发动、LP 变动。
 * 不做任何"连招挖掘"——原样记录，理解交给上游的 LLM 摘要。
 *
 * <p>每一步都必须带<b>自然语言描述</b>（{@link Step#desc()}，卡名经 {@link CardFacts} 解析），
 * 只有编号的记录没有价值：人看不懂，模型也只能瞎猜。
 */
public final class ComboStepReader {

    /** combo 录入固定记录用户回合；agent 强制后手，用户回合恒为第 1 回合。 */
    public static final int USER_TURN = 1;

    /**
     * 一条已描述化的操作。
     *
     * @param kind     事件类型（TURN_STARTED / PHASE_ENTERED / CARD_MOVED / EFFECT_ACTIVATED / LP_CHANGED）
     * @param desc     自然语言描述（主字段：给人看、给 LLM 看、存库）
     * @param passcode 涉及的卡（无则 0）
     * @param from/to  移动起止区（原始格式 OPP:MAIN_MONSTER_ZONE#2，无则 null）
     * @param player   "用户" / "AI"（按座位归属，可空）
     */
    public record Step(long seq, int turn, String phase, String kind, String desc,
                       long passcode, String fromZone, String toZone, String position,
                       String player) {
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * @param duelLog 对局日志 JSONL
     * @param facts   卡名解析（可空：解析不出时用"未知卡(编号)"）
     */
    public static List<Step> readUserTurn(Path duelLog, CardFacts facts) throws IOException {
        List<String> lines = Files.readAllLines(duelLog, StandardCharsets.UTF_8);
        int agentSeat = -1;
        List<Step> steps = new ArrayList<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            JsonNode root;
            try {
                root = MAPPER.readTree(line);
            } catch (RuntimeException e) {
                continue;
            }
            int turn = root.path("turn").asInt(-1);
            if (turn != USER_TURN) {
                if (turn == 0 && "DUEL_STARTED".equals(root.path("kind").asText())) {
                    agentSeat = agentSeat(root);
                }
                continue;
            }
            long seq = root.path("seq").asLong();
            String phase = root.path("phase").asText("");
            String kind = root.path("kind").asText("");
            JsonNode payload = root.path("payload");
            int playerSeat = seatOf(payload);
            switch (kind) {
                case "TURN_STARTED" -> steps.add(new Step(seq, turn, phase, kind,
                        "用户回合开始", 0, null, null, null, "用户"));
                case "PHASE_ENTERED" -> steps.add(new Step(seq, turn, phase, kind,
                        "进入" + phaseLabel(phase), 0, null, null, null, null));
                case "CARD_MOVED" -> {
                    long passcode = payload.path("passcode").asLong();
                    String from = textOrNull(payload, "fromZone");
                    String to = textOrNull(payload, "toZone");
                    String position = textOrNull(payload, "toPosition");
                    steps.add(new Step(seq, turn, phase, kind,
                            cardMoveDesc(payload, facts), passcode, from, to, position,
                            seatLabel(from, to, agentSeat)));
                }
                case "EFFECT_ACTIVATED" -> steps.add(new Step(seq, turn, phase, kind,
                        "发动效果：" + cardName(facts, payload.path("passcode").asLong()),
                        payload.path("passcode").asLong(), null, null, null,
                        playerSeat == agentSeat ? "AI" : "用户"));
                case "LP_CHANGED" -> {
                    int delta = payload.path("delta").asInt();
                    int after = payload.path("lpAfter").asInt();
                    steps.add(new Step(seq, turn, phase, kind,
                            (playerSeat == agentSeat ? "AI" : "用户") + " LP " + (delta > 0 ? "+" : "")
                                    + delta + "（" + after + "）",
                            0, null, null, null, playerSeat == agentSeat ? "AI" : "用户"));
                }
                default -> {
                    // STATE_SNAPSHOT / DECISION_*（AI 侧决策）等不进录入
                }
            }
        }
        return List.copyOf(steps);
    }

    /** DUEL_STARTED 的 players[0] 是观察者（agent）自己——见 DuelLogBridge#emitDuelStarted。 */
    private static int agentSeat(JsonNode duelStarted) {
        JsonNode first = duelStarted.path("payload").path("players").path(0);
        return first.isMissingNode() ? -1 : first.path("seat").asInt(-1);
    }

    private static int seatOf(JsonNode payload) {
        String player = payload.path("player").asText("");
        return player.startsWith("P") ? parseIntSafe(player.substring(1)) : -1;
    }

    private static int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 卡牌移动的描述：按"从哪到哪 + 表示形式"挑一个说人话的动词，不做效果猜测。 */
    private static String cardMoveDesc(JsonNode payload, CardFacts facts) {
        long passcode = payload.path("passcode").asLong();
        String from = textOrNull(payload, "fromZone");
        String to = textOrNull(payload, "toZone");
        String position = textOrNull(payload, "toPosition");
        String card = cardName(facts, passcode);
        String toLabel = to == null ? null : zoneLabel(to);
        String pos = positionLabel(position);
        String target = toLabel == null ? "" : toLabel + (pos.isEmpty() ? "" : "（" + pos + "）");

        if (from == null && to != null) {
            return card + " 出现在 " + target;
        }
        if (from != null && to == null) {
            return card + " 离开 " + zoneLabel(from);
        }
        String fromKind = zoneKind(from);
        String toKind = zoneKind(to);
        if ("DECK".equals(fromKind) && "HAND".equals(toKind)) {
            return passcode > 0 ? "抽卡：《" + card + "》" : "从卡组抽 1 张卡";
        }
        if ("HAND".equals(fromKind) && toKind != null && toKind.endsWith("MONSTER_ZONE")) {
            boolean faceDown = position != null && position.contains("FACE_DOWN");
            return (faceDown ? "盖放" : "召唤/特殊召唤") + "《" + card + "》到 " + target;
        }
        if ("HAND".equals(fromKind) && "SPELL_TRAP_ZONE".equals(toKind)) {
            boolean faceDown = position != null && position.contains("FACE_DOWN");
            return faceDown ? "盖放《" + card + "》到 " + target
                    : "发动魔法《" + card + "》（置于 " + target + "）";
        }
        if ("GRAVEYARD".equals(toKind)) {
            return "《" + card + "》从 " + zoneLabel(from) + " 送去墓地";
        }
        if ("BANISHED".equals(toKind)) {
            return "《" + card + "》从 " + zoneLabel(from) + " 除外";
        }
        if ("HAND".equals(toKind)) {
            return "《" + card + "》回到手牌";
        }
        return "《" + card + "》从 " + zoneLabel(from) + " 移动到 " + target;
    }

    private static String seatLabel(String from, String to, int agentSeat) {
        boolean aiInvolved = (from != null && from.startsWith("SELF"))
                || (to != null && to.startsWith("SELF"));
        return agentSeat >= 0 && aiInvolved ? "AI" : null;
    }

    private static String textOrNull(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        return node == null || node.isNull() || node.asText("").isBlank() ? null : node.asText();
    }

    private static String cardName(CardFacts facts, long passcode) {
        if (passcode <= 0) {
            return "未知卡";
        }
        String name = facts == null ? null
                : facts.byPasscode(passcode).map(CardFact::name).orElse(null);
        return name == null || name.isBlank() ? "未知卡(" + passcode + ")" : name;
    }

    /** "OPP:MAIN_MONSTER_ZONE#2" → "怪兽区#2"；AI 自己的区加前缀。 */
    private static String zoneLabel(String raw) {
        String[] parts = raw.split(":", 2);
        String side = parts.length > 1 ? parts[0] : "";
        String kindWithSeq = parts.length > 1 ? parts[1] : parts[0];
        String kind = kindWithSeq.replaceAll("#.*", "");
        String seq = kindWithSeq.contains("#") ? kindWithSeq.substring(kindWithSeq.indexOf('#')) : "";
        String label = switch (kind) {
            case "DECK" -> "卡组";
            case "EXTRA_DECK" -> "额外卡组";
            case "HAND" -> "手牌";
            case "MAIN_MONSTER_ZONE" -> "怪兽区";
            case "EXTRA_MONSTER_ZONE" -> "额外怪兽区";
            case "SPELL_TRAP_ZONE" -> "魔陷区";
            case "PENDULUM_ZONE" -> "灵摆区";
            case "FIELD_ZONE" -> "场地魔法区";
            case "GRAVEYARD" -> "墓地";
            case "BANISHED" -> "除外区";
            default -> kind;
        };
        String full = label + seq;
        return "SELF".equals(side) ? "AI方·" + full : full;
    }

    private static String zoneKind(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split(":", 2);
        return (parts.length > 1 ? parts[1] : parts[0]).replaceAll("#.*", "");
    }

    private static String positionLabel(String position) {
        if (position == null || position.isBlank()) {
            return "";
        }
        return switch (position) {
            case "FACE_UP_ATTACK" -> "表侧攻击";
            case "FACE_DOWN_ATTACK" -> "里侧攻击";
            case "FACE_UP_DEFENSE" -> "表侧守备";
            case "FACE_DOWN_DEFENSE" -> "盖放（守备）";
            default -> position.toLowerCase(Locale.ROOT);
        };
    }

    private static String phaseLabel(String phase) {
        return switch (phase == null ? "" : phase) {
            case "DRAW_PHASE" -> "抽卡阶段";
            case "STANDBY_PHASE" -> "准备阶段";
            case "MAIN_PHASE_1" -> "主要阶段 1";
            case "BATTLE_START_STEP" -> "战斗开始步骤";
            case "BATTLE_STEP" -> "战斗步骤";
            case "DAMAGE_STEP" -> "伤害步骤";
            case "DAMAGE_CALCULATION" -> "伤害计算";
            case "BATTLE_END_STEP" -> "战斗结束步骤";
            case "MAIN_PHASE_2" -> "主要阶段 2";
            case "END_PHASE" -> "结束阶段";
            default -> phase == null ? "?" : phase;
        };
    }

    private ComboStepReader() {
    }
}
