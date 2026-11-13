package com.github.coldgust.ygoagent.llm.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.ArrayList;
import java.util.List;

/**
 * 规划器对话记忆（{@code ChatMemory} 自定义实现，AiServices 全托管 add/读取）。
 * 整局<b>连续不清空</b>：跨回合上下文由修剪策略承载，不再是旧设计的"每回合清空 + 文字接力"。
 *
 * <p>修剪规则是<b>整局前缀缓存的前提</b>：超过 24 条时保留 [0..1]
 * （system + 整局静态资料——只在首个锚点前种入一次）与最近 12 条（补偿工具消息占比）。
 * 修剪起点必须落在 UserMessage 边界上：工具交换
 * （AiMessage(toolExecutionRequests) ↔ ToolExecutionResultMessage）成对出现，
 * 只要从交换中间不切，配对就完整。
 */
public final class PlannerChatMemory implements ChatMemory {

    /** 修剪保留线：[0]=system、[1]=锚点消息（整局静态资料在其中）。 */
    private static final int KEEP_FROM = 2;
    /** 修剪后保留的最近消息条数。 */
    private static final int KEEP_RECENT = 12;
    /** 触发修剪的历史上限。 */
    private static final int TRIM_THRESHOLD = 24;

    private final Object id;
    private final List<ChatMessage> messages = new ArrayList<>();

    public PlannerChatMemory(Object id) {
        this.id = id;
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public synchronized void add(ChatMessage message) {
        if (message instanceof SystemMessage system) {
            // 框架每次调用都会把 system 注入 memory：同内容去重，保持 [0]=system 的钉住不变式
            if (!messages.isEmpty() && messages.getFirst()
                    instanceof SystemMessage first) {
                if (first.text().equals(system.text())) {
                    return;
                }
                messages.set(0, system);
                return;
            }
            messages.add(0, system);
            return;
        }
        messages.add(message);
        trim();
    }

    @Override
    public synchronized List<ChatMessage> messages() {
        return List.copyOf(messages);
    }

    @Override
    public synchronized void clear() {
        messages.clear();
    }

    /**
     * 锯齿修剪：超过阈值才剪，剪完 = [0..keepFrom) + 最近 KEEP_RECENT 条（对齐到消息边界）。
     * 切口两端都必须落在 UserMessage 边界上：工具交换（AiMessage(toolCalls) ↔ ToolResult）
     * 成对出现，从交换中间切会劈开配对；对不齐就把切口外扩到最近的边界。
     */
    private void trim() {
        if (messages.size() <= TRIM_THRESHOLD) {
            return;
        }
        int end = messages.size() - KEEP_RECENT;
        int from = KEEP_FROM;
        while (from < end && !(messages.get(from) instanceof UserMessage)) {
            from++;
        }
        while (end < messages.size() && !(messages.get(end) instanceof UserMessage)) {
            end++;
        }
        if (from >= end) {
            return; // 找不到两侧都干净的切点（有界：工具轮上限决定了这种窗口有限）
        }
        messages.subList(from, end).clear();
    }
}
