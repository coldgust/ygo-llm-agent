package com.github.coldgust.ygoagent.decision.planexec.llm;

import java.util.List;

/**
 * combo 库的唯一数据源：PG 里的实战录入（ygo-play-server 的 ComboRecordService 落库）。
 *
 * <p>两类消费场景，接口刻意分开：
 * <ul>
 *   <li><b>己方注入</b>（{@link #recentByArchetype}）：前端选定的主题，按创建时间倒序取最近 N 条，
 *       整段注入规划器上下文——"这套卡组实际怎么打"；</li>
 *   <li><b>对手检索</b>（{@link #search}）：模型根据对手出现的卡推断出对手主题（或拿到关键卡名）时，
 *       按主题名 + 关键卡牌名捞参考路线——"对面这套卡组通常会怎么展开"。一次检索最多几条由调用方定（工具侧上限 3）。</li>
 * </ul>
 *
 * <p>记录里的每一步都必须是人话；关键卡牌（keyCards）由 LLM 在生成摘要时一并给出。
 */
public interface RecordedComboSource {

    /** 一条实战录入。{@code summary} 可空（LLM 摘要生成失败时只有步骤）。 */
    record Entry(long id, String archetype, int stepCount, String summary,
                 List<String> keyCards, List<String> stepDescriptions) {

        public Entry {
            keyCards = keyCards == null ? List.of() : List.copyOf(keyCards);
            stepDescriptions = stepDescriptions == null ? List.of() : List.copyOf(stepDescriptions);
        }
    }

    /** 数据源是否可用（表不存在/库不可达/无记录都算不可用，调用方静默跳过）。 */
    boolean available();

    /** 库里实际有哪些主题（供 find_combos 在模型没传 archetype 时列可用值）。 */
    List<String> archetypes();

    /** 按主题取最近的录入记录（创建时间倒序），供"选定主题注入上下文"。 */
    List<Entry> recentByArchetype(String archetype, int limit);

    /**
     * find_combos 检索：主题名匹配或关键卡牌名包含即命中（卡名双向包含，
     * "闪刀" 能中 "闪刀姬"，反之亦然）。两参可只给其一。
     */
    List<Entry> search(String archetype, String keyCard, int limit);
}
