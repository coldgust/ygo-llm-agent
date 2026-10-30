package com.github.coldgust.ygoprosdk.smoke;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.decision.ChoiceSelection;
import com.github.coldgust.ygodomain.deck.Deck;
import com.github.coldgust.ygodomain.duel.DuelResult;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygoprosdk.deck.YdkParser;
import com.github.coldgust.ygoprosdk.session.DefaultFallbackPolicy;
import com.github.coldgust.ygoprosdk.session.DuelListener;
import com.github.coldgust.ygoprosdk.session.DuelSession;
import com.github.coldgust.ygoprosdk.session.RoomOptions;
import com.github.coldgust.ygoprosdk.session.SessionConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 冒烟客户端：连本地房间宿主走全流程。
 *
 * <ul>
 *   <li>默认交互决策模式：非平凡决策点写入 /tmp/sdk-choice.txt，
 *       由外部决策者在 /tmp/sdk-decision.txt 写一行指令（300 秒超时走保底）；
 *       无强制效果的连锁询问自动"不连锁"。</li>
 *   <li>--auto：自动对打模式，内置覆盖面优先的简单策略，无需外部决策。</li>
 *   <li>--create：由本端建房并在双方就绪后自动开局（配合 --password 设房间密码）。</li>
 * </ul>
 *
 * <pre>
 * java SmokeClient [host] [port] [名字] [.ydk] [first|second] [--create] [--auto] [--password 密码]
 * 决策示例（写进 /tmp/sdk-decision.txt）：main summon 0 / main end / battle attack 0 / chain decline
 * </pre>
 */
public final class SmokeClient {

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 7911;
        String name = "ygo-sdk-smoke";
        String ydkPath = null;
        String move = "second";
        String password = "";
        boolean createRoom = false;
        boolean autoPlay = false;
        boolean rawFrames = false;

        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--create" -> createRoom = true;
                case "--auto" -> autoPlay = true;
                case "--raw" -> rawFrames = true;
                case "--password" -> password = ++i < args.length ? args[i] : "";
                default -> positional.add(args[i]);
            }
        }
        if (positional.size() > 0) host = positional.get(0);
        if (positional.size() > 1) port = Integer.parseInt(positional.get(1));
        if (positional.size() > 2) name = positional.get(2);
        if (positional.size() > 3) ydkPath = positional.get(3);
        if (positional.size() > 4) move = positional.get(4);

        Deck deck = ydkPath != null
                ? YdkParser.parse(Files.readString(Path.of(ydkPath)))
                : Deck.empty();
        var issues = deck.validate();
        if (!issues.isEmpty()) {
            System.out.println("[卡组校验] " + issues);
        }

        String duelLogPath = "/tmp/ygo-duel-log/" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".jsonl";
        SessionConfig.Builder builder = SessionConfig.builder(host, name)
                .port(port)
                .deck(deck)
                .firstPlayer("first".equalsIgnoreCase(move)
                        ? SessionConfig.FirstMove.FIRST : SessionConfig.FirstMove.SECOND)
                .answerDelayMillis(150)
                .joinPassword(password)
                .duelLog(Path.of(duelLogPath))
                .duelLogRawFrames(rawFrames);
        System.out.println("对局记录 → " + duelLogPath);
        if (createRoom) {
            RoomOptions room = new RoomOptions(0, 0, 5, 0, false, false, false,
                    8000, 5, 1, 180, name, password);
            builder.createRoom(room);
        }
        DuelSession session = DuelSession.start(builder.build());
        session.addListener(new DuelListener() {
            @Override
            public void onDuelEnd(DuelResult result) {
                System.out.println("[结束] 赢家=" + result.winner() + " 原因=" + result.reason());
            }

            @Override
            public void onError(Throwable error) {
                System.out.println("[错误] " + error);
            }
        });

        System.out.println("已连接（座位 = " + session.selfSeat() + "），等待决斗位…");
        long waitStart = System.currentTimeMillis();
        while (!(session.selfSeat() == 0 || session.selfSeat() == 1)) {
            if (System.currentTimeMillis() - waitStart > 60_000) {
                System.out.println("60 秒内未获得决斗位，退出。");
                session.close();
                return;
            }
            Thread.sleep(300);
        }
        session.uploadDeck(deck);
        session.setReady(true);
        if (createRoom) {
            System.out.println("卡组已上传并就绪（座位 " + session.selfSeat() + "），房主自动开局…");
            for (int i = 0; i < 30 && session.stage() != DuelSession.Stage.DUEL; i++) {
                session.startDuel();
                Thread.sleep(1_000);
            }
        } else {
            System.out.println("卡组已上传并就绪（座位 " + session.selfSeat() + "），等待房主开局…");
        }

        // 等新一局真正开打（MSG_START 重置 + NEW_TURN 起回合），避开上一局残留状态
        while (!(session.stage() == DuelSession.Stage.DUEL
                && session.state().turnNumber() >= 1
                && !session.state().isFinished())) {
            if (session.stage() == DuelSession.Stage.CLOSED) {
                System.out.println("连接已关闭，退出。");
                return;
            }
            Thread.sleep(300);
        }

        InteractiveBridge bridge = new InteractiveBridge();

        if (autoPlay) {
            System.out.println("== 对局开始，进入自动对打循环 ==");
            long lastTurnPrinted = -1;
            while (!session.state().isFinished()) {
                ChoiceRequest request = session.pollChoice();
                if (request == null) {
                    lastTurnPrinted = printTurnIfNew(session, lastTurnPrinted);
                    if (session.stage() == DuelSession.Stage.CLOSED) {
                        break;
                    }
                    Thread.sleep(100);
                    continue;
                }
                // 无强制效果的连锁询问自动"不连锁"（高频且无决策价值）
                if (request instanceof ChoiceRequest.ChainRequest chain && !chain.hasForcedEffect()) {
                    session.respond(new ChoiceSelection.ChainSelection(ChoiceSelection.ChainAnswer.Decline.INSTANCE));
                    continue;
                }
                System.out.print(bridge.describe(session.state(), request));
                ChoiceSelection selection = autoAnswer(request);
                try {
                    session.respond(selection);
                    System.out.println("[自动应答] " + selection.getClass().getSimpleName());
                } catch (Exception e) {
                    System.out.println("[应答失败] " + e);
                }
            }
        } else {
            System.out.println("== 对局开始，进入交互决策循环 ==");
            long lastTurnPrinted = -1;
            while (!session.state().isFinished()) {
                ChoiceRequest request = session.pollChoice();
                if (request == null) {
                    lastTurnPrinted = printTurnIfNew(session, lastTurnPrinted);
                    if (session.stage() == DuelSession.Stage.CLOSED) {
                        break;
                    }
                    Thread.sleep(150);
                    continue;
                }

                // 无强制效果的连锁询问自动"不连锁"（高频且无决策价值）
                if (request instanceof ChoiceRequest.ChainRequest chain && !chain.hasForcedEffect()) {
                    System.out.println("[自动] 连锁询问 → 不连锁（候选 " + chain.options().size() + " 个）");
                    session.respond(new ChoiceSelection.ChainSelection(ChoiceSelection.ChainAnswer.Decline.INSTANCE));
                    continue;
                }

                String dump = bridge.describe(session.state(), request);
                System.out.print(dump);
                System.out.println("[等待决策：写入 /tmp/sdk-decision.txt，300 秒超时走保底]");
                bridge.ask(dump);
                String line = bridge.lastDecision();
                ChoiceSelection selection = bridge.parse(request, line);
                if (selection == null) {
                    selection = DefaultFallbackPolicy.get().fallback(request, 0);
                    System.out.println("[使用保底] " + selection.getClass().getSimpleName());
                }
                try {
                    session.respond(selection);
                    System.out.println("[已应答] " + selection);
                } catch (Exception e) {
                    System.out.println("[应答失败] " + e);
                }
            }
        }

        System.out.println("对局结束。10 秒后关闭会话。");
        Thread.sleep(10_000);
        var info = session.duelLog().duelInfo();
        var decisions = session.duelLog().decisions(null);
        System.out.printf("[对局记录] 事件=%d 决策=%d（我方 %d） 回合=%d 胜者=%s/%s%n",
                session.duelLog().latestSeq(), decisions.size(),
                decisions.stream().filter(d -> info.players().stream()
                        .anyMatch(p -> p.seat() == session.selfSeat())
                        && d.player().equals("P" + session.selfSeat())).count(),
                info.totalTurns(), info.winner(), info.endReason());
        session.close();
    }

    /** 打印回合概况（回合号变化时），返回新的已打印回合号。 */
    private static long printTurnIfNew(DuelSession session, long lastTurnPrinted) {
        var state = session.state();
        if (state.turnNumber() != lastTurnPrinted) {
            var s = state.self();
            var o = state.opponent();
            System.out.printf("[回合%d %s %s] 我方 LP%d 手%d 怪%d / 对方 LP%d 手%d 怪%d%n",
                    state.turnNumber(), state.turnPlayer(), state.phase(),
                    s.lifePoints(), s.handCount(), state.monsters(PlayerSide.SELF).size(),
                    o.lifePoints(), o.handCount(), state.monsters(PlayerSide.OPPONENT).size());
            return state.turnNumber();
        }
        return lastTurnPrinted;
    }

    /**
     * 自动对打策略（覆盖面优先，构造上取自候选集合故合法）：
     * 主阶段 召唤→发动→盖怪→盖魔陷→进战阶→结束；战阶攻击→结束；
     * 选卡/祭品取下限；区域按 count 取（count=0 但有候选区时按 1 并标注，疑似解码 bug）；
     * 其余类型交给内置保底。
     */
    static ChoiceSelection autoAnswer(ChoiceRequest request) {
        if (request instanceof ChoiceRequest.MainPhaseRequest m) {
            if (!m.normalSummonable().isEmpty()) {
                return new ChoiceSelection.MainPhaseSelection(new ChoiceSelection.Summon(m.normalSummonable().getFirst()));
            }
            if (!m.activatable().isEmpty()) {
                return new ChoiceSelection.MainPhaseSelection(new ChoiceSelection.Activate(m.activatable().getFirst()));
            }
            if (!m.monsterSettable().isEmpty()) {
                return new ChoiceSelection.MainPhaseSelection(new ChoiceSelection.SetMonster(m.monsterSettable().getFirst()));
            }
            if (!m.spellTrapSettable().isEmpty()) {
                return new ChoiceSelection.MainPhaseSelection(new ChoiceSelection.SetSpellTrap(m.spellTrapSettable().getFirst()));
            }
            if (m.canEnterBattlePhase()) {
                return new ChoiceSelection.MainPhaseSelection(ChoiceSelection.EnterBattlePhase.INSTANCE);
            }
            return new ChoiceSelection.MainPhaseSelection(ChoiceSelection.EndTurn.INSTANCE);
        }
        if (request instanceof ChoiceRequest.BattlePhaseRequest bt) {
            if (!bt.attackable().isEmpty()) {
                return new ChoiceSelection.BattlePhaseSelection(new ChoiceSelection.Attack(bt.attackable().getFirst()));
            }
            return new ChoiceSelection.BattlePhaseSelection(ChoiceSelection.EndBattlePhase.INSTANCE);
        }
        if (request instanceof ChoiceRequest.CardSelectRequest s) {
            int take = Math.min(s.min(), s.candidates().size());
            return new ChoiceSelection.CardSelection(new ArrayList<>(s.candidates().subList(0, take)));
        }
        if (request instanceof ChoiceRequest.TributeSelectRequest t) {
            int take = Math.min(t.min(), t.candidates().size());
            return new ChoiceSelection.TributeSelection(new ArrayList<>(t.candidates().subList(0, take)));
        }
        if (request instanceof ChoiceRequest.PlaceRequest p) {
            int take = p.count();
            if (take == 0 && !p.availableZones().isEmpty()) {
                System.out.println("[可疑] Place 请求 count=0 但有 " + p.availableZones().size()
                        + " 个候选区（疑似解码 bug），按 1 作答");
                take = 1;
            }
            take = Math.min(take, p.availableZones().size());
            return new ChoiceSelection.PlaceSelection(new ArrayList<>(p.availableZones().subList(0, take)));
        }
        return DefaultFallbackPolicy.get().fallback(request, 0);
    }

    private SmokeClient() {
    }
}
