package com.github.coldgust.ygoplay;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.decision.ChoiceSelection;
import com.github.coldgust.ygodomain.duel.CardInstance;
import com.github.coldgust.ygodomain.duel.DuelResult;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygoagent.context.ContextPackBuilder;
import com.github.coldgust.ygoagent.context.DuelMathService;
import com.github.coldgust.ygoprosdk.session.DuelListener;
import com.github.coldgust.ygoprosdk.session.ResponseSource;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;

public final class BattlefieldReporter implements DuelListener {

    private final PrintWriter file;
    private final ContextPackBuilder packer; // 可能为 null（无卡库时退化为只报卡号）
    private final AtomicInteger blocks = new AtomicInteger();

    private volatile DuelState last;

    private BattlefieldReporter(PrintWriter file, ContextPackBuilder packer) {
        this.file = file;
        this.packer = packer;
    }

    public static BattlefieldReporter open(Path path, CardFacts facts) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        PrintWriter writer = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8), true);
        ContextPackBuilder packer = facts == null ? null
                : new ContextPackBuilder(facts, new DuelMathService());
        return new BattlefieldReporter(writer, packer);
    }


    @Override
    public void onStateUpdate(DuelState state) {
        this.last = state;
    }

    @Override
    public void onChoiceAvailable(ChoiceRequest request) {
        DuelState state = last;
        if (state == null) {
            return;
        }
        emit("════════ [%s] 战场 #%d ｜ 第 %d 回合 ｜ %s ｜ %s ｜ 待决策：%s ════════"
                .formatted(LocalTime.now().withNano(0), blocks.incrementAndGet(),
                        state.turnNumber(), state.isMyTurn() ? "我方回合" : "对方回合",
                        state.phase(), request.getClass().getSimpleName()));
        emit(anchorOf(state));
        emit(countCheck(state));
        emit("");
    }

    @Override
    public void onResponseSent(ChoiceRequest request, ChoiceSelection selection,
                               ResponseSource source, int retryCount) {
        String suffix = retryCount > 0 ? "（重试 " + retryCount + "）" : "";
        emit("↳ 应答 [" + source + "] " + selection + suffix);
    }

    @Override
    public void onDuelEnd(DuelResult result) {
        emit("🏁 对局结束：" + (result == null ? "未知" : result.winner() + "/" + result.reason()));
    }

    @Override
    public void onError(Throwable error) {
        emit("⚠ 协议告警：" + error);
    }

    @Override
    public void onDisconnected() {
        emit("🔌 连接关闭");
    }


    private String anchorOf(DuelState state) {
        return packer != null ? packer.anchorPack(state).stripTrailing() : fallbackAnchor(state);
    }

    private String fallbackAnchor(DuelState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("[局面] 第 ").append(state.turnNumber()).append(" 回合，")
                .append(state.isMyTurn() ? "我方回合" : "对方回合")
                .append("，阶段 ").append(state.phase())
                .append("；LP 我 ").append(state.self().lifePoints())
                .append(" / 对 ").append(state.opponent().lifePoints()).append('\n');
        sb.append("[我方手牌] ").append(state.self().handCount()).append(" 张：")
                .append(codes(state.self().hand())).append('\n');
        sb.append("[我方场面] ").append(zoneSummary(state, PlayerSide.SELF)).append('\n');
        sb.append("[对手场面] ").append(zoneSummary(state, PlayerSide.OPPONENT)).append('\n');
        sb.append("[对手手牌] ").append(state.opponent().handCount()).append(" 张（不可见）");
        return sb.toString();
    }

    private String countCheck(DuelState state) {
        return "[核对] 我：怪兽 %d、魔陷 %d（盖 %d / 表侧 %d）、灵摆 %d、场地 %d、手牌 %d ｜ 对：怪兽 %d、魔陷 %d、手牌 %d"
                .formatted(
                        state.monsters(PlayerSide.SELF).size(),
                        state.spellTraps(PlayerSide.SELF).size(),
                        faceDown(state.spellTraps(PlayerSide.SELF)),
                        faceUp(state.spellTraps(PlayerSide.SELF)),
                        state.pendulumZones(PlayerSide.SELF).size(),
                        state.fieldSpell(PlayerSide.SELF).isPresent() ? 1 : 0,
                        state.self().handCount(),
                        state.monsters(PlayerSide.OPPONENT).size(),
                        state.spellTraps(PlayerSide.OPPONENT).size(),
                        state.opponent().handCount());
    }

    private static long faceDown(List<CardInstance> cards) {
        return cards.stream().filter(c -> c.position() == null || !c.position().isFaceUp()).count();
    }

    private static long faceUp(List<CardInstance> cards) {
        return cards.stream().filter(c -> c.position() != null && c.position().isFaceUp()).count();
    }

    private static String zoneSummary(DuelState state, PlayerSide side) {
        StringJoiner sj = new StringJoiner("；");
        sj.add("怪兽：" + slotList(state.monsters(side)));
        sj.add("魔陷：" + slotList(state.spellTraps(side)));
        state.fieldSpell(side).ifPresent(f -> sj.add("场地：" + label(f)));
        return sj.toString();
    }

    private static String slotList(List<CardInstance> cards) {
        if (cards.isEmpty()) {
            return "（空）";
        }
        StringJoiner sj = new StringJoiner("、");
        cards.forEach(c -> sj.add("#" + (c.zone() == null ? "?" : c.zone().sequence())
                + " " + label(c)));
        return sj.toString();
    }

    private static String label(CardInstance c) {
        String name = c.isUnknown() ? "未知卡" : "#" + c.passcode();
        String facing = c.position() == null ? "朝向未知"
                : (c.position().isFaceUp() ? "表侧" : "里侧");
        return name + "(" + facing + ")";
    }

    private static String codes(List<CardInstance> cards) {
        if (cards.isEmpty()) {
            return "（空）";
        }
        StringJoiner sj = new StringJoiner("、");
        for (CardInstance c : cards) {
            sj.add(c.isUnknown() ? "未知" : String.valueOf(c.passcode()));
        }
        return sj.toString();
    }

    private void emit(String line) {
        System.out.println(line);
        System.out.flush();
        file.println(line);
        file.flush();
    }
}
