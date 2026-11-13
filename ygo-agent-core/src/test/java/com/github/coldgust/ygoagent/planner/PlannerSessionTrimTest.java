package com.github.coldgust.ygoagent.planner;

import com.github.coldgust.ygoagent.context.ContextPackBuilder;
import com.github.coldgust.ygoagent.context.DuelMathService;
import com.github.coldgust.ygoagent.context.RuleDigest;
import com.github.coldgust.ygoagent.llm.ChatClient;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.llm.VerboseLogSettings;
import com.github.coldgust.ygoagent.model.ChoiceIntent;
import com.github.coldgust.ygoagent.model.DecisionPoint;
import com.github.coldgust.ygoagent.pipeline.PipelineSettings;
import com.github.coldgust.ygoagent.pipeline.TestDuels;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 特征测试：规划器历史修剪必须保留 [0]=system、[1]=整局静态资料——静态消息是跨回合
 * 前缀缓存的前提，被修剪掉缓存就断了。历史 >24 条时保留 [0..1] 与最近 8 条。
 */
class PlannerSessionTrimTest {

    private final VerboseLog trace = new VerboseLog(VerboseLogSettings.off());

    private static PipelineSettings cfg() {
        PipelineSettings d = PipelineSettings.defaults(Path.of("data/card_rag/cards.jsonl"),
                "https://api.deepseek.com", "test-key", "deepseek-chat");
        // checkpoint 配额放到 30，才能在测试里把历史推过 24 条触发修剪
        return new PipelineSettings(true, d.baseUrl(), d.apiKey(), d.model(),
                0, 0, 20_000, 30, 0.80, 300,
                d.plannerCallTimeoutMs(), d.plannerCooldownMs(),
                d.cardRagPath(), d.judgeMaxTokens(), true, true);
    }

    private static DecisionPoint pointOf(int turn) {
        var state = TestDuels.state(turn, true, List.of(), List.of(), List.of(), List.of(), 8000, 8000);
        return TestDuels.mainPhasePoint(state, List.of(), List.of());
    }

    private static String planJson() {
        return "{\"thinking\": \"t\", \"goal\": \"g\", \"main_line\": {\"steps\": []}, \"confidence\": 0.5}";
    }

    @Test
    void historyTrimKeepsSystemAndStaticMaterial() {
        List<List<ChatMessage>> calls = new ArrayList<>();
        AtomicInteger calls0 = new AtomicInteger();
        ChatClient chat = (messages, options) -> {
            calls.add(List.copyOf(messages));
            return calls0.getAndIncrement() == 0 ? planJson() : "{\"keep\": true}";
        };
        // 带规则速查 → 整局静态资料非空 → 静态消息入 history
        PlannerSession planner = new PlannerSession(chat,
                ContextPackBuilder.builder(TestDuels.facts(), new DuelMathService())
                        .ruleDigest(RuleDigest.TEXT)
                        .advisory(true)
                        .build(),
                TestDuels.facts(), cfg(), trace);

        var point = pointOf(2);
        planner.observePoint(point);
        planner.drainForTests(); // 锚点（回复 0 = 计划）
        for (int i = 0; i < 15; i++) {
            planner.observeAnswered(point, new ChoiceIntent.EndTurn());
            planner.flushEvents();
            planner.drainForTests(); // 事件 checkpoint ×15，历史被推过 24 条
        }

        List<ChatMessage> last = calls.getLast();
        assertInstanceOf(SystemMessage.class, last.getFirst(), "[0] 必须是 system");
        UserMessage staticMessage = (UserMessage) last.get(1);
        assertTrue(staticMessage.singleText().contains("[整局资料]"),
                "[1] 必须是整局静态资料（跨回合前缀缓存的前提）:" + staticMessage.singleText().substring(0, 60));
        // 修剪是锯齿语义：超过 24 条才剪，剪完 = [0..1] + 最近 8 条 → 在 10..25 之间振荡
        assertTrue(last.size() <= 25, "历史必须有界: " + last.size());
        assertTrue(last.size() >= 10, "最近 8 条与静态资料要留存: " + last.size());
        assertTrue(calls.get(1).size() >= 3, "早期调用未被修剪（system+静态+锚点）");
    }
}
