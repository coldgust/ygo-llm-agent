package com.github.coldgust.ygoagent.planner;

import com.github.coldgust.ygoagent.context.ContextPackBuilder;
import com.github.coldgust.ygoagent.context.DuelMathService;
import com.github.coldgust.ygoagent.context.DuelTimeline;
import com.github.coldgust.ygoagent.llm.ChatClient;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.llm.VerboseLogSettings;
import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.pipeline.PipelineSettings;
import com.github.coldgust.ygoagent.pipeline.TestDuels;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：事件批必须带上对局记录里的"对方做了什么"——pendingEvents 只有我方应答，
 * 没有记录行时规划器在回合中段看不到对手的动作。
 */
class PlannerSessionRecordBatchTest {

    private final VerboseLog trace = new VerboseLog(VerboseLogSettings.off());

    private static PipelineSettings settings() {
        PipelineSettings s = PipelineSettings.defaults(Path.of("data/card_rag/cards.jsonl"),
                "https://api.deepseek.com", "test-key", "deepseek-chat");
        return new PipelineSettings(true, s.baseUrl(), s.apiKey(), s.model(), 0, 0,
                s.waitPerTurnMs(), s.maxCheckpointsPerTurn(), s.similarityThreshold(),
                s.judgeTimeoutMs(), s.plannerCallTimeoutMs(), s.plannerCooldownMs(),
                s.cardRagPath(), s.judgeMaxTokens(), true, true);
    }

    private static DecisionPoint pointOf(int turn) {
        var state = TestDuels.state(turn, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        return TestDuels.mainPhasePoint(state, List.of(), List.of());
    }

    @Test
    void eventBatchCarriesRecordLinesBeyondOwnAnswers() {
        List<String> prompts = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger calls = new AtomicInteger();
        ChatClient chat = (messages, options) -> {
            ChatMessage last = messages.getLast();
            prompts.add(last instanceof UserMessage user ? user.singleText() : last.toString());
            return calls.getAndIncrement() == 0
                    ? "{\"thinking\": \"t\", \"goal\": \"g\", \"main_line\": {\"steps\": []}, \"confidence\": 0.5}"
                    : "{\"keep\": true}";
        };

        // 可变假记录：锚点构建时为空（水位对齐 0），之后才追加事件 → 事件批要能增量拿到
        List<DuelTimeline.Entry> store = Collections.synchronizedList(new ArrayList<>());
        DuelTimeline timeline = new DuelTimeline() {
            @Override
            public List<DuelTimeline.Entry> since(long seq) {
                synchronized (store) {
                    return store.stream().filter(e -> e.seq() > seq).toList();
                }
            }

            @Override
            public List<DuelTimeline.Entry> turn(int turnNumber) {
                synchronized (store) {
                    return store.stream().filter(e -> e.turn() == turnNumber).toList();
                }
            }

            @Override
            public long latestSeq() {
                synchronized (store) {
                    return store.stream().mapToLong(DuelTimeline.Entry::seq).max().orElse(0);
                }
            }
        };
        ContextPackBuilder packer = ContextPackBuilder.builder(
                TestDuels.facts(), new DuelMathService())
                .advisory(true)
                .timeline(timeline)
                .build();
        PlannerSession planner = new PlannerSession(chat, packer, TestDuels.facts(),
                settings(), null, (com.github.coldgust.ygoagent.llm.DecisionTools) null,
                timeline, trace);

        DecisionPoint point = pointOf(3);
        planner.observePoint(point);
        planner.drainForTests();
        int callsAfterAnchor = calls.get();

        // 锚点之后对手动了（LP 变动），我方也答了一手：事件批两者都要有
        store.add(new DuelTimeline.Entry.LpChanged(5, 3, DuelTimeline.Side.SELF, -100, 7900));
        planner.observeAnswered(point, new ChoiceIntent.EndTurn());
        planner.flushEvents();
        planner.drainForTests();
        assertEquals(callsAfterAnchor + 1, calls.get(), "有新事件就要发一次 checkpoint");
        String batch = prompts.getLast();
        assertTrue(batch.contains("应答"), "我方应答行保留:" + batch);
        assertTrue(batch.contains("我方 LP -100（→7900）"), "记录行要补对手/状态变化:" + batch);

        // 没有新事件、没有新记录 → 不再烧 checkpoint
        planner.flushEvents();
        planner.drainForTests();
        assertEquals(callsAfterAnchor + 1, calls.get(), "空批不发 checkpoint");
    }
}
