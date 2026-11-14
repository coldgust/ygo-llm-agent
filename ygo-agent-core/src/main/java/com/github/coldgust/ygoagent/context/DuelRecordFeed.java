package com.github.coldgust.ygoagent.context;

import com.github.coldgust.ygoagent.knowledge.CardFacts;
import com.github.coldgust.ygoagent.llm.memory.DuelChatMemory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 决斗记录流写入方（docs/planner-judge-memory-redesign.md §5）：把对局历史以
 * **逐事件原文**追加进 {@link DuelChatMemory}——全部在调用前拉取（pull-only），
 * 规划器与决策器拿到的是同一条记录流。
 *
 * <p>2026-11-14 用户指令：记录流里**不放决策确认信息**。「我方：连锁发动 X［可选：…］」
 * 这类行是决策收尾的自述，不是对局事实——规划器只需要对局记录（引擎事件行本身就写了
 * 「我方：发动 X」），决策器自己的 AI 回答（choice/rationale JSON）也已经在它的对话里。
 * 因此本类不再有任何 push 入口：记录 = 时间线事件，决策 = 各自的对话。
 *
 * <p>记录行全部是 RECORD 条目，永不修剪、只增不减；每个挂载的 memory 持有独立水位
 * （互不推进）；重答属同一次使用，不重复拉取。记录入史与 LLM 配额解耦：
 * 纯内存追加，从不触发调用。
 */
public final class DuelRecordFeed {

    private final DuelTimeline timeline;
    private final DuelRecordSections sections;
    private final List<Attachment> attachments = new ArrayList<>();

    /** 挂载的 memory 与它的私有拉取水位。 */
    private static final class Attachment {
        final DuelChatMemory memory;
        long watermark;

        Attachment(DuelChatMemory memory) {
            this.memory = memory;
        }
    }

    /** @param timeline 决斗过程记录端口；null = 无对手事件可拉。 */
    public DuelRecordFeed(DuelTimeline timeline, CardFacts facts) {
        this.timeline = timeline;
        this.sections = new DuelRecordSections(facts, new CardText(facts));
    }

    /** 挂载一个 memory（独立水位；重复挂载同一实例被忽略）。 */
    public synchronized void attach(DuelChatMemory memory) {
        if (attachments.stream().noneMatch(a -> a.memory == memory)) {
            attachments.add(new Attachment(memory));
        }
    }

    /**
     * 调用前拉取：对手/系统/我方动作的引擎事件增量（RECORD 批消息，逐事件原文）。
     * timeline 未接线时为空操作。
     */
    public synchronized void fillBeforeCall(DuelChatMemory memory, int currentTurn) {
        Attachment attachment = attachmentOf(memory);
        if (attachment == null || timeline == null) {
            return;
        }
        List<DuelTimeline.Entry> entries = timeline.since(attachment.watermark);
        if (!entries.isEmpty()) {
            List<String> lines = sections.lines(entries);
            if (!lines.isEmpty()) {
                attachment.memory.appendRecord(incrementBlock(lines));
            }
            attachment.watermark = timeline.latestSeq();
        }
    }

    /**
     * 记录增量块：一个〔决斗记录〕标记 + 逐行缩进的事实。
     *
     * <p>标记只起"这段是记录流"的作用，不加"自上次以来对局推进"之类的旁白——
     * 记录本身是账本，不是叙述；多出来的引导句每块都要占一行，还会让模型以为
     * 那是"摘要"而不是"逐条事实"（2026-11-14 用户反馈）。
     */
    private static String incrementBlock(List<String> lines) {
        return lines.stream()
                .map(line -> "  " + line)
                .collect(Collectors.joining("\n", "〔决斗记录〕\n", ""));
    }

    private Attachment attachmentOf(DuelChatMemory memory) {
        for (Attachment attachment : attachments) {
            if (attachment.memory == memory) {
                return attachment;
            }
        }
        return null;
    }
}
