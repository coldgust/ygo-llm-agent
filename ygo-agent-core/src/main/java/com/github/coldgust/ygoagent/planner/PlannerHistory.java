package com.github.coldgust.ygoagent.planner;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 规划器对话历史的持有者（模块内部协作类型）。
 *
 * <p>拥有唯一的 {@code List<ChatMessage>}：段轮换时的组装（system → 整局静态资料 → 段间接力 →
 * 回合锚点）与防膨胀修剪都在这里。修剪规则是<b>跨回合前缀缓存的前提</b>：
 * 超过 24 条时保留 [0..1]（system + 整局静态资料）与最近 8 条——静态消息被剪掉，
 * 服务端前缀缓存就断了。只在 planner 单线程（worker）上调用。
 */
final class PlannerHistory {

    /** 修剪保留线：[0]=system、[1]=整局静态资料（无静态资料时 [1] 为锚点，规则不变）。 */
    private static final int KEEP_FROM = 2;
    /** 修剪后保留的最近消息条数。 */
    private static final int KEEP_RECENT = 8;
    /** 触发修剪的历史上限。 */
    private static final int TRIM_THRESHOLD = 24;

    private final List<ChatMessage> history = new ArrayList<>();

    /**
     * 段轮换：清空并按固定顺序组装。
     *
     * @param staticMaterial 整局静态资料（可空 = 不插入该消息）
     * @param handoff        段间接力摘要（可空 = 首段没有）
     * @param anchorRequest  回合锚点请求（已含模板与禁 keep 后缀）
     */
    void startSegment(SystemMessage system, String staticMaterial,
                      String handoff, String anchorRequest) {
        history.clear();
        history.add(system);
        if (staticMaterial != null && !staticMaterial.isBlank()) {
            history.add(UserMessage.from(staticMaterial));
        }
        if (handoff != null && !handoff.isBlank()) {
            history.add(UserMessage.from("===上一段摘要接力===\n" + handoff));
        }
        history.add(UserMessage.from(anchorRequest));
    }

    void add(UserMessage message) {
        history.add(message);
    }

    /** 记录模型回复并按阈值修剪（锯齿语义：超过阈值才剪，剪完 = [0..keepFrom) + 最近 KEEP_RECENT 条）。 */
    void addAssistant(String raw) {
        history.add(AiMessage.from(raw));
        if (history.size() > TRIM_THRESHOLD) {
            history.subList(KEEP_FROM, Math.max(KEEP_FROM, history.size() - KEEP_RECENT)).clear();
        }
    }

    List<ChatMessage> snapshot() {
        return List.copyOf(history);
    }
}
