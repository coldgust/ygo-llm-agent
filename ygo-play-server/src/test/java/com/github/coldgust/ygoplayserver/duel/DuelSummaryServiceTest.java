package com.github.coldgust.ygoplayserver.duel;

import com.github.coldgust.ygoagent.decision.planexec.llm.ChatClient;
import com.github.coldgust.ygoplay.DuelRunSpec;
import com.github.coldgust.ygoplayserver.duel.summary.DuelNarrative;
import com.github.coldgust.ygoplayserver.duel.summary.DuelTraceReader;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 赛后复盘：文档头必须确定，正文优先由 LLM 写，LLM 不可用时退回模板。
 */
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

    @Test
    void summaryContainsDomainAndIdentity() throws Exception {
        DuelSummaryService service = new DuelSummaryService(new MockEnvironment());
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
            ChatClient fake = (messages, options) -> {
                seen[0] = messages.get(1).content();
                return """
                        ## 一句话结论
                        先手无展开点空过，第二回合被对手铺场 OTK。

                        ## 局面走向
                        T1 我方只有灰流丽与增殖的G，盖双纽闪门后结束回合。
                        T2 对手连续特召并清掉后场，我方无力阻断，LP 归零。

                        ## 转折点
                        T2 对手第一次特召时没有可用的手坑，此后场面彻底失控。
                        """;
            };

            String summary = new DuelSummaryService(new MockEnvironment(), fake)
                    .build("d2", spec(log, trace), null);

            // 头是代码给的，正文是模型给的
            assertTrue(summary.startsWith("domain: duel\n"), summary);
            assertTrue(summary.contains("## 一句话结论"), summary);
            assertFalse(summary.contains("由模板生成"), summary);
            // 素材里必须有可读动作与计划目标，否则模型只能瞎猜
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
            // 少了固定小节 → 判为不可用
            ChatClient rambling = (messages, options) -> "这局打得还行，下次注意资源。";

            String summary = new DuelSummaryService(new MockEnvironment(), rambling)
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
            ChatClient fake = (messages, options) -> {
                called[0] = true;
                return "## 一句话结论\nxx\n\n## 局面走向\nxx\n";
            };
            MockEnvironment env = new MockEnvironment().withProperty("DUEL_SUMMARY_MODE", "template");

            String summary = new DuelSummaryService(env, fake).build("d4", spec(log, trace), null);

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
            ChatClient broken = (messages, options) -> {
                throw new ChatClient.ChatException("boom");
            };

            String summary = new DuelSummaryService(new MockEnvironment(), broken)
                    .build("d5", spec(log, trace), null);

            assertTrue(summary.contains("由模板生成"), summary);
        } finally {
            Files.deleteIfExists(trace);
            Files.deleteIfExists(log);
        }
    }

    @Test
    void narrativeCapsRunawayActionLists() {
        // 40 条相同动作：不能把提示词撑爆
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

    /** 模型判定不值得入库 → draft.worth=false，且判定节不出现在最终文档里。 */
    @Test
    void judgedDraftRejectsWorthlessDuelAndStripsVerdict() throws Exception {
        Path trace = Files.createTempFile("duel-trace", ".jsonl");
        Path log = Files.createTempFile("duel-log", ".jsonl");
        try {
            Files.writeString(trace, TRACE);
            Files.writeString(log, "");
            ChatClient fake = (messages, options) -> """
                    ## 一句话结论
                    双方均无展开，资源互换后平推结束，没有任何转折。

                    ## 局面走向
                    T1 我方通召后直接结束回合，对手同样无展开，双方互空过。
                    T2 双方继续空过，最终对手以场面打点完成斩杀。

                    ## 入库判定
                    判定：不值得入库
                    全程无失误、无罕见局面，没有可沉淀的经验。
                    """;

            DuelSummaryService.Draft draft = new DuelSummaryService(new MockEnvironment(), fake)
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

    /** 判定缺失（模型忘了写）按值得处理——正文本身是完整的。 */
    @Test
    void missingVerdictDefaultsToWorth() throws Exception {
        Path trace = Files.createTempFile("duel-trace", ".jsonl");
        Path log = Files.createTempFile("duel-log", ".jsonl");
        try {
            Files.writeString(trace, TRACE);
            Files.writeString(log, "");
            // 够长（looksUsable 要求 ≥80 字）但没有「入库判定」节 → 按值得处理
            ChatClient fake = (messages, options) -> """
                    ## 一句话结论
                    我方后手顶住对手第一波场面后，抓住对手断资源的一回合完成反打并带走比赛。

                    ## 局面走向
                    T1 对手通召铺场盖两张后场结束回合，我方手牌一般，先保住场面资源再找突破点。
                    T2 对手续攻未果，我方抽到关键牌后一回合完成翻盘。
                    """;

            DuelSummaryService.Draft draft = new DuelSummaryService(new MockEnvironment(), fake)
                    .draft("d9", spec(log, trace), null);

            assertTrue(draft.worth());
        } finally {
            Files.deleteIfExists(trace);
            Files.deleteIfExists(log);
        }
    }

    /** auto 策略下 LLM 完全不可用（模板兜底不含判断）→ 不产出可入库正文。 */
    @Test
    void judgedDraftWithoutLlmIsNotMarkedFromLlm() throws Exception {
        Path log = Files.createTempFile("duel-log", ".jsonl");
        try {
            Files.writeString(log, "");
            DuelSummaryService.Draft draft = new DuelSummaryService(new MockEnvironment())
                    .draft("d10", spec(log, Path.of("/tmp/none.jsonl")), null);

            assertFalse(draft.fromLlm());
            assertFalse(draft.worth());
            assertTrue(draft.text().contains("由模板生成"));
        } finally {
            Files.deleteIfExists(log);
        }
    }
}
