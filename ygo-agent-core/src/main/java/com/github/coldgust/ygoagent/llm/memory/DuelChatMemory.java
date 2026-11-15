package com.github.coldgust.ygoagent.llm.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.ArrayList;
import java.util.List;

/**
 * 决斗对话记忆（{@code ChatMemory} 自定义实现，AiServices 全托管 add/读取）。
 * docs/planner-judge-memory-redesign.md §4：静态资料钉住前缀、决斗记录以追加形式进 memory、
 * 战场信息走一次性快照槽不入 memory。
 *
 * <p>条目分三类（分类在内存结构里，不靠消息文本标记——标记词会泄漏进 prompt 且不可靠）：
 * <ul>
 *   <li>{@code PINNED}：[0]=system、[1]=整局静态资料，构造时种入，永不修剪；</li>
 *   <li>{@code RECORD}：决斗记录流（DuelRecordFeed 逐事件原文追加，含我方应答行与
 *       对手/系统事件行，回合边界行）——**永不修剪、只增不减**，这就是提示词里
 *       "决斗记录逐条追加、只增不减"的机制载体（2026-11-14 实测修订：摘要聚合
 *       "对方上场 6 张/…等"是信息损失，用户指令直推逐事件原文）；</li>
 *   <li>{@code NORMAL}：请求行、assistant 回复、工具对——受修剪窗口管辖
 *       （推理的近期性靠它，事实账本靠 RECORD）。</li>
 * </ul>
 *
 * <p>一次性快照槽：{@link #stageOnce} 放入的内容在 {@link #messages()} 里追加在 durable 末尾，
 * 绝不进 durable——战场信息/候选/建议"只用一次、不入 memory"的机制载体。
 * 与 wire 的相对位置（DefaultAiServices#assembleMessages，1.23.0）：框架先读 messages()
 * 再 append 本次 user 消息——首轮请求里快照位于本次请求行**之前**；
 * 工具轮重读 messages() 时快照垫底（在最新工具对之后）。
 * 生命周期由编排代码显式管理（stage → 调用含工具轮/重答 → finally 丢弃）；
 * 决策器正常路径每时刻至多一次在途调用，规划器 worker 单线程——但决策器<b>超时路径</b>
 * 会遗留后台任务（{@code cancel(true)} 打不断阻塞中的 HTTP 调用），与下一手并发，
 * 因此决策器必须用 {@link #dropIfStaged}（引用守卫）丢弃快照：遗留任务只能清掉自己
 * stage 的那份，不会误清下一手的快照；无并发顾虑的调用方（规划器）仍可用
 * {@link #dropEphemeral} 无条件清空。
 *
 * <p>修剪（锯齿）：NORMAL 超过阈值才剪；剪后保留 pinned + 全部 RECORD + 最近 keepRecent 条
 * NORMAL；保留边界向后扩到 UserMessage 边界，工具对（AiMessage(toolCalls) ↔ ToolResult 成对
 * 出现）不从中间劈开。追加为主，服务端前缀缓存跨调用有效。
 */
public final class DuelChatMemory implements ChatMemory {

    /** 决策器窗口（调优参数走代码默认，docs §4.3）。 */
    public static final int JUDGE_TRIM_THRESHOLD = 16;
    public static final int JUDGE_KEEP_RECENT = 8;

    /** 规划器窗口（幕僚上下文更多：详情窗口大于决策器，docs §3 不对称表）。 */
    public static final int PLANNER_TRIM_THRESHOLD = 24;
    public static final int PLANNER_KEEP_RECENT = 12;

    private enum Kind { PINNED, RECORD, NORMAL }

    private record Entry(ChatMessage message, Kind kind) {
    }

    private final Object id;
    private final int trimThreshold;
    private final int keepRecent;
    private final List<Entry> entries = new ArrayList<>();
    /** 一次性快照槽（不入 durable）。 */
    private UserMessage ephemeral;

    public DuelChatMemory(Object id, int trimThreshold, int keepRecent,
                          SystemMessage system, UserMessage staticMaterial) {
        this.id = id;
        this.trimThreshold = trimThreshold;
        this.keepRecent = keepRecent;
        if (system != null) {
            entries.add(new Entry(system, Kind.PINNED));
        }
        if (staticMaterial != null && !staticMaterial.singleText().isBlank()) {
            entries.add(new Entry(staticMaterial, Kind.PINNED));
        }
    }

    /** 决策器实例：小窗口（每手都调用，快照与工具对话为主）。 */
    public static DuelChatMemory forJudge(Object id, SystemMessage system, UserMessage staticMaterial) {
        return new DuelChatMemory(id, JUDGE_TRIM_THRESHOLD, JUDGE_KEEP_RECENT, system, staticMaterial);
    }

    /** 规划器实例：大窗口（回合级调用，建议与记录的连续性优先）。 */
    public static DuelChatMemory forPlanner(Object id, SystemMessage system, UserMessage staticMaterial) {
        return new DuelChatMemory(id, PLANNER_TRIM_THRESHOLD, PLANNER_KEEP_RECENT, system, staticMaterial);
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public synchronized void add(ChatMessage message) {
        if (message instanceof SystemMessage system) {
            // 框架每次调用都会把 system 注入 memory：同内容去重，保持 [0]=system 的钉住不变式
            if (!entries.isEmpty() && entries.getFirst().message() instanceof SystemMessage first) {
                if (first.text().equals(system.text())) {
                    return;
                }
                entries.set(0, new Entry(system, Kind.PINNED));
                return;
            }
            entries.add(0, new Entry(system, Kind.PINNED));
            return;
        }
        entries.add(new Entry(message, Kind.NORMAL));
        trim();
    }

    @Override
    public synchronized List<ChatMessage> messages() {
        List<ChatMessage> out = new ArrayList<>(entries.size() + 1);
        for (Entry entry : entries) {
            out.add(entry.message());
        }
        if (ephemeral != null) {
            out.add(ephemeral);
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized void clear() {
        entries.clear();
        ephemeral = null;
    }

    /** 调用前放入一次性快照（不入 durable）；重入即替换（重答共用同一份快照）。 */
    public void stageOnce(UserMessage snapshot) {
        synchronized (this) {
            ephemeral = snapshot;
        }
    }

    /** 调用结束（finally）丢弃快照。 */
    public void dropEphemeral() {
        synchronized (this) {
            ephemeral = null;
        }
    }

    /**
     * 带引用守卫的快照丢弃：仅当槽内快照仍是调用方 stage 的那一份（同一引用）时才置空。
     * 决策器超时会遗留后台任务（{@code cancel(true)} 打不断阻塞中的 HTTP 调用），
     * 其 finally 可能晚于下一手 {@link #stageOnce} 执行——引用不同即不丢，避免误清下一手的快照
     * （LlmJudge 竞态，2026-11-15 缺陷修复）。
     */
    public void dropIfStaged(UserMessage snapshot) {
        synchronized (this) {
            if (ephemeral == snapshot) {
                ephemeral = null;
            }
        }
    }

    /** 决斗记录行（由 DuelRecordFeed 写入；RECORD 条目，永不修剪、只增不减）。 */
    public void appendRecord(String text) {
        synchronized (this) {
            entries.add(new Entry(UserMessage.from(text), Kind.RECORD));
        }
    }

    /**
     * 修剪：NORMAL 超过阈值才剪。剪后 = pinned + 全部 RECORD + 最近 keepRecent 条 NORMAL。
     * 保留边界从"第 (n-keepRecent) 条 NORMAL"向后扩到 UserMessage——第一条保留的 NORMAL 是
     * UserMessage，工具对就不会从中间被劈开；被丢弃范围里的 RECORD 原位保留（只丢 NORMAL）。
     */
    private void trim() {
        List<Integer> normalIdx = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).kind() == Kind.NORMAL) {
                normalIdx.add(i);
            }
        }
        if (normalIdx.size() <= trimThreshold) {
            return;
        }
        int keepFrom = normalIdx.get(normalIdx.size() - keepRecent);
        while (keepFrom < entries.size()
                && !(entries.get(keepFrom).message() instanceof UserMessage)) {
            keepFrom++;
        }
        List<Entry> kept = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (i >= normalIdx.getFirst() && i < keepFrom && entry.kind() == Kind.NORMAL) {
                continue;
            }
            kept.add(entry);
        }
        entries.clear();
        entries.addAll(kept);
    }
}
