package com.github.coldgust.ygoagent.llm.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 决斗对话记忆的条目模型（docs/planner-judge-memory-redesign.md §4）：
 * pinned/摘要不修剪、NORMAL 窗口锯齿修剪、快照槽不入 durable。
 */
class DuelChatMemoryTest {

    private static DuelChatMemory memory() {
        return DuelChatMemory.forJudge("t",
                SystemMessage.from("系统提示"), UserMessage.from("静态资料"));
    }

    private static List<String> texts(DuelChatMemory memory) {
        return memory.messages().stream()
                .map(m -> m instanceof SystemMessage s ? s.text()
                        : m instanceof UserMessage u ? u.singleText()
                        : ((AiMessage) m).text())
                .toList();
    }

    @Test
    void pinnedSeedFormsThePrefix() {
        DuelChatMemory memory = memory();
        memory.add(UserMessage.from("请求"));
        memory.add(AiMessage.from("回复"));

        List<String> seen = texts(memory);
        assertEquals(List.of("系统提示", "静态资料", "请求", "回复"), seen);
    }

    @Test
    void repeatedSystemInjectionIsDeduplicated() {
        DuelChatMemory memory = memory();
        memory.add(SystemMessage.from("系统提示"));
        memory.add(SystemMessage.from("系统提示"));

        assertEquals(List.of("系统提示", "静态资料"), texts(memory),
                "框架每次调用注入 system：同内容去重，钉住不变式不破");
    }

    @Test
    void ephemeralStagedIsVisibleOnceAndNeverEntersDurable() {
        DuelChatMemory memory = memory();
        memory.add(UserMessage.from("请求1"));
        memory.add(AiMessage.from("回复1"));

        memory.stageOnce(UserMessage.from("[决策快照] 局面…"));
        List<String> during = texts(memory);
        assertTrue(during.getLast().contains("[决策快照]"),
                "快照追加在序列末尾（跟随最新请求行）: " + during);
        assertEquals(5, during.size(), "4 条 durable + 1 条快照");

        memory.dropEphemeral();
        assertEquals(List.of("系统提示", "静态资料", "请求1", "回复1"), texts(memory),
                "快照用完即弃，绝不进 durable");
    }

    @Test
    void recordLinesSurviveTrimWhileNormalsAreDropped() {
        DuelChatMemory memory = new DuelChatMemory("t", 6, 2,
                SystemMessage.from("系统提示"), UserMessage.from("静态资料"));
        // 8 条 NORMAL（超过阈值 6）：中间插一条决斗记录
        for (int i = 1; i <= 4; i++) {
            memory.add(UserMessage.from("请求" + i));
            memory.add(AiMessage.from("回复" + i));
            if (i == 2) {
                memory.appendRecord("〔决斗记录〕我方：不连锁（不响应 对方 发动 X）");
            }
        }

        List<String> seen = texts(memory);
        assertTrue(seen.contains("系统提示") && seen.contains("静态资料"), "pinned 永不修剪: " + seen);
        assertTrue(seen.stream().anyMatch(s -> s.contains("〔决斗记录〕")), "记录流永不修剪: " + seen);
        assertFalse(seen.contains("请求1"), "超出窗口的旧 NORMAL 被丢弃: " + seen);
        assertFalse(seen.contains("回复1"), "超出窗口的旧 NORMAL 被丢弃: " + seen);
        assertTrue(seen.contains("请求4") && seen.contains("回复4"), "最近窗口保留: " + seen);
        // keepRecent=2 → 保留最近 2 条 NORMAL（请求4/回复4），切口落在 UserMessage 上
        long normals = seen.stream().filter(s -> s.startsWith("请求") || s.startsWith("回复")).count();
        assertEquals(2, normals, "剪后 NORMAL 数 = keepRecent: " + seen);
    }

    @Test
    void trimDoesNotSplitToolPairs() {
        DuelChatMemory memory = new DuelChatMemory("t", 4, 2,
                SystemMessage.from("系统提示"), UserMessage.from("静态资料"));
        // 第一组交换带工具对（AiMessage(toolReq) + 工具结果在真实框架里也是独立消息，
        // 这里用两条 AiMessage 模拟"非 UserMessage 开头的成对消息"）
        memory.add(UserMessage.from("请求1"));
        memory.add(AiMessage.from("toolReq"));
        memory.add(AiMessage.from("toolResult"));
        memory.add(UserMessage.from("请求2"));
        memory.add(AiMessage.from("回复2"));
        memory.add(UserMessage.from("请求3"));
        memory.add(AiMessage.from("回复3"));

        List<String> seen = texts(memory);
        // 请求1 的工具对要么整体保留、要么整体丢弃：切口对齐 UserMessage 边界
        boolean keptPair = seen.contains("toolReq") && seen.contains("toolResult");
        boolean droppedPair = !seen.contains("toolReq") && !seen.contains("toolResult");
        assertTrue(keptPair || droppedPair, "工具对不从中间劈开: " + seen);
        if (droppedPair) {
            assertFalse(seen.contains("请求1"), "丢弃从请求行开始（UserMessage 边界）: " + seen);
        }
    }

    @Test
    void clearResetsEverythingIncludingEphemeral() {
        DuelChatMemory memory = memory();
        memory.add(UserMessage.from("请求"));
        memory.stageOnce(UserMessage.from("[决策快照]"));
        memory.clear();
        assertTrue(memory.messages().isEmpty(), "clear 全清");
    }
}
