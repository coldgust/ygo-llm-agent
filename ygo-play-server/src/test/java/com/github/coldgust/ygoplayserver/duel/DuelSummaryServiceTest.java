package com.github.coldgust.ygoplayserver.duel;

import com.github.coldgust.ygoagent.llm.LlmEndpoint;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.llm.VerboseLogSettings;
import com.github.coldgust.ygoagent.llm.LlmChatException;
import com.github.coldgust.ygoagent.llm.service.NarratorAgent;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import com.github.coldgust.ygoplay.DuelRunSpec;
import com.github.coldgust.ygoplayserver.duel.summary.DuelNarrative;
import com.github.coldgust.ygoplayserver.duel.summary.DuelTraceReader;
import org.junit.jupiter.api.Test;
import com.github.coldgust.ygoplayserver.config.LightRagProperties;
import com.github.coldgust.ygoplayserver.config.DuelSummaryProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuelSummaryServiceTest {

    private static final String TRACE = """
            {"kind":"PLAN_EVENT","turn":1,"goal":"先手空过，盖双纽闪门留作资源","thinking":"无展开点，只能盖后场"}
            {"kind":"EXEC_DECISION","seq":3,"turn":1,"requestLabel":"主要阶段","explanation":"没有能特召的怪，先盖后场"}
            {"kind":"SUBMITTED","seq":3,"turn":1,"requestLabel":"主要阶段","levelLabel":"照计划","submitted":"盖放魔陷 双纽闪门(34433770)我方手牌","fallback":false}
            {"kind":"SUBMITTED","seq":4,"turn":2,"requestLabel":"连锁响应","levelLabel":"决策器判断","submitted":"不响应","fallback":false}
            {"kind":"SUBMITTED","seq":5,"turn":2,"requestLabel":"连锁响应","levelLabel":"决策器判断","submitted":"不响应","fallback":false}
            """;

    private static DuelRunSpec spec(Path log, Path trace) {
        return new DuelRunSpec("127.0.0.1", 7911, "n", "second",
                null, null, "SKY_STRIKER", false, log, trace, null);
    }

    private static DuelSummaryService service(DuelSummaryProperties props, NarratorAgent chat) {
        return new DuelSummaryService(new LightRagProperties(null, null, 8_000L), props, chat, new VerboseLog(VerboseLogSettings.off()));
    }

    private static final LlmEndpoint ENDPOINT =
            new LlmEndpoint("http://localhost:9", "k", "deepseek-chat");

    /** 固定回复的假模型：记录最后一条 user 消息（narrative 返回 String，无格式指令）。 */
    private static NarratorAgent markdownService(String[] seen, boolean[] called, String reply) {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                if (called != null) {
                    called[0] = true;
                }
                if (seen != null) {
                    seen[0] = request.messages().stream()
                            .filter(m -> m instanceof UserMessage)
                            .map(m -> ((UserMessage) m).singleText())
                            .reduce((a, b) -> b)
                            .orElse("");
                }
                return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
            }
        };
        return new NarratorAgent(model, "sys", null);
    }

    private static NarratorAgent brokenService() {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new LlmChatException("boom");
            }
        };
        return new NarratorAgent(model, "sys", null);
    }

    @Test
    void summaryContainsDomainAndIdentity() throws Exception {
        DuelSummaryService service = service(DuelSummaryProperties.defaults(), null);
        Path log = Files.createTempFile("duel-summary", ".jsonl");
        try {
            Files.writeString(log, "");
            String summary = service.build("d1", spec(log, Path.of("/tmp/does-not-exist.jsonl")), null);

            assertTrue(summary.contains("domain: duel"), summary);
            assertTrue(summary.contains("duel: d1"), summary);
            assertTrue(summary.contains("myArchetype: SKY_STRIKER"), summary);
            assertTrue(summary.contains("move: 后手"), summary);
        } finally {
            Files.deleteIfExists(log);
        }
    }

    @Test
    void llmBodyIsAppendedAfterDeterministicHeader() throws Exception {
        Path trace = Files.createTempFile("duel-trace", ".jsonl");
        Path log = Files.createTempFile("duel-log", ".jsonl");
        try {
            Files.writeString(trace, TRACE);
            Files.writeString(log, "");
            String[] seen = new String[1];
            NarratorAgent fake = markdownService(seen, null, """
                        ## 一句话结论
                        先手无展开点空过，第二回合被对手铺场 OTK。

                        ## 局面走向
                        T1 我方只有灰流丽与增殖的G，盖双纽闪门后结束回合。
                        T2 对手连续特召并清掉后场，我方无力阻断，LP 归零。

                        ## 转折点
                        T2 对手第一次特召时没有可用的手坑，此后场面彻底失控。
                        """);

            String summary = service(DuelSummaryProperties.defaults(), fake)
                    .build("d2", spec(log, trace), null);

            assertTrue(summary.startsWith("domain: duel\n"), summary);
            assertTrue(summary.contains("## 一句话结论"), summary);
            assertFalse(summary.contains("由模板生成"), summary);
            assertTrue(seen[0].contains("盖放魔陷 双纽闪门"), seen[0]);
            assertTrue(seen[0].contains("先手空过，盖双纽闪门留作资源"), seen[0]);
            assertTrue(seen[0].contains("没有能特召的怪，先盖后场"), seen[0]);
        } finally {
            Files.deleteIfExists(trace);
            Files.deleteIfExists(log);
        }
    }

    @Test
    void unusableLlmOutputFallsBackToTemplate() throws Exception {
        Path trace = Files.createTempFile("duel-trace", ".jsonl");
        Path log = Files.createTempFile("duel-log", ".jsonl");
        try {
            Files.writeString(trace, TRACE);
            Files.writeString(log, "");
            NarratorAgent rambling = markdownService(null, null, "这局打得还行，下次注意资源。");

            String summary = service(DuelSummaryProperties.defaults(), rambling)
                    .build("d3", spec(log, trace), null);

            assertTrue(summary.contains("由模板生成"), summary);
            assertTrue(summary.contains("双纽闪门"), summary);
        } finally {
            Files.deleteIfExists(trace);
            Files.deleteIfExists(log);
        }
    }

    @Test
    void templateModeNeverCallsLlm() throws Exception {
        Path trace = Files.createTempFile("duel-trace", ".jsonl");
        Path log = Files.createTempFile("duel-log", ".jsonl");
        try {
            Files.writeString(trace, TRACE);
            Files.writeString(log, "");
            boolean[] called = new boolean[1];
            NarratorAgent fake = markdownService(null, called, "## 一句话结论\nxx\n\n## 局面走向\nxx\n");
            DuelSummaryProperties props = new DuelSummaryProperties(null, "template", null, null, null, null);

            String summary = service(props, fake).build("d4", spec(log, trace), null);

            assertFalse(called[0], "template 模式不该调模型");
            assertTrue(summary.contains("由模板生成"), summary);
        } finally {
            Files.deleteIfExists(trace);
            Files.deleteIfExists(log);
        }
    }

    @Test
    void chatFailureDegradesToTemplate() throws Exception {
        Path trace = Files.createTempFile("duel-trace", ".jsonl");
        Path log = Files.createTempFile("duel-log", ".jsonl");
        try {
            Files.writeString(trace, TRACE);
            Files.writeString(log, "");
            NarratorAgent broken = brokenService();

            String summary = service(DuelSummaryProperties.defaults(), broken)
                    .build("d5", spec(log, trace), null);

            assertTrue(summary.contains("由模板生成"), summary);
        } finally {
            Files.deleteIfExists(trace);
            Files.deleteIfExists(log);
        }
    }

    @Test
    void narrativeCapsRunawayActionLists() {
        StringBuilder trace = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            trace.append("{\"kind\":\"SUBMITTED\",\"seq\":").append(i)
                    .append(",\"turn\":2,\"requestLabel\":\"连锁响应\",\"levelLabel\":\"唯一合法\"")
                    .append(",\"submitted\":\"不响应\",\"fallback\":false}\n");
        }
        var view = DuelTraceReader
                .read(List.of(trace.toString().split("\n")));
        var narrative = DuelNarrative.build(
                "d6", spec(Path.of("/tmp/none.jsonl"), Path.of("/tmp/none-trace.jsonl")),
                null, null, view, 8_000);

        assertTrue(narrative.timeline().contains("连续 40 次相同"), narrative.timeline());
        assertTrue(narrative.timeline().contains("对手回合"), narrative.timeline());
        assertTrue(narrative.timeline().length() < 400, "折叠后应很短：" + narrative.timeline().length());
    }

    @Test
    void narrativeMarksOurTurnFromMainPhaseRequests() {
        var view = DuelTraceReader.read(List.of(
                "{\"kind\":\"SUBMITTED\",\"seq\":1,\"turn\":1,\"requestLabel\":\"主要阶段\","
                        + "\"levelLabel\":\"照计划\",\"submitted\":\"结束回合\",\"fallback\":false}"));
        var narrative = DuelNarrative.build(
                "d7", spec(Path.of("/tmp/none.jsonl"), Path.of("/tmp/none-trace.jsonl")),
                null, null, view, 8_000);

        assertTrue(narrative.timeline().contains("我方回合"), narrative.timeline());
    }

    @Test
    void judgedDraftRejectsWorthlessDuelAndStripsVerdict() throws Exception {
        Path trace = Files.createTempFile("duel-trace", ".jsonl");
        Path log = Files.createTempFile("duel-log", ".jsonl");
        try {
            Files.writeString(trace, TRACE);
            Files.writeString(log, "");
            NarratorAgent fake = markdownService(null, null, """
                    ## 一句话结论
                    双方均无展开，资源互换后平推结束，没有任何转折。

                    ## 局面走向
                    T1 我方通召后直接结束回合，对手同样无展开，双方互空过。
                    T2 双方继续空过，最终对手以场面打点完成斩杀。

                    ## 入库判定
                    判定：不值得入库
                    全程无失误、无罕见局面，没有可沉淀的经验。
                    """);

            DuelSummaryService.Draft draft = service(DuelSummaryProperties.defaults(), fake)
                    .draft("d8", spec(log, trace), null);

            assertFalse(draft.worth(), "判定不值得入库");
            assertTrue(draft.fromLlm());
            assertFalse(draft.text().contains("入库判定"), "元信息不进对局库：" + draft.text());
            assertTrue(draft.text().contains("## 一句话结论"));
        } finally {
            Files.deleteIfExists(trace);
            Files.deleteIfExists(log);
        }
    }

    @Test
    void missingVerdictDefaultsToWorth() throws Exception {
        Path trace = Files.createTempFile("duel-trace", ".jsonl");
        Path log = Files.createTempFile("duel-log", ".jsonl");
        try {
            Files.writeString(trace, TRACE);
            Files.writeString(log, "");
            NarratorAgent fake = markdownService(null, null, """
                    ## 一句话结论
                    我方后手顶住对手第一波场面后，抓住对手断资源的一回合完成反打并带走比赛。

                    ## 局面走向
                    T1 对手通召铺场盖两张后场结束回合，我方手牌一般，先保住场面资源再找突破点。
                    T2 对手续攻未果，我方抽到关键牌后一回合完成翻盘。
                    """);

            DuelSummaryService.Draft draft = service(DuelSummaryProperties.defaults(), fake)
                    .draft("d9", spec(log, trace), null);

            assertTrue(draft.worth());
        } finally {
            Files.deleteIfExists(trace);
            Files.deleteIfExists(log);
        }
    }

    @Test
    void judgedDraftWithoutLlmIsNotMarkedFromLlm() throws Exception {
        Path log = Files.createTempFile("duel-log", ".jsonl");
        try {
            Files.writeString(log, "");
            DuelSummaryService.Draft draft = service(DuelSummaryProperties.defaults(), null)
                    .draft("d10", spec(log, Path.of("/tmp/none.jsonl")), null);

            assertFalse(draft.fromLlm());
            assertFalse(draft.worth());
            assertTrue(draft.text().contains("由模板生成"));
        } finally {
            Files.deleteIfExists(log);
        }
    }
}
