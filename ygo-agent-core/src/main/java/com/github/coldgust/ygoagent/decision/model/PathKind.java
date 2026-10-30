package com.github.coldgust.ygoagent.decision.model;

/**
 * 决策路径来源（写入 trace，用于 A/B 与复盘）。
 *
 * <p>命名原则：一眼看出"<b>这次是谁做的决定</b>"，而不是描述内部机制。
 * 旧名的问题是：{@code REFLEX} 让人以为"反应快"，实际是"没得选"；
 * {@code LLM_ONESHOT} 是术语，看不出是"临时叫一次模型"；{@code PLAN_EXACT} 描述的是匹配算法，
 * 而使用者只关心"是不是照计划走的"。
 *
 * <p>每个值带一个中文标签（{@link #label()}），日志与前端直接用，不必再翻译一遍。
 */
public enum PathKind {
    /** 引擎只给了唯一合法（或结构上无歧义）的选项：没有策略判断的余地。 */
    ONLY_CHOICE("唯一合法"),
    /** 连招热索引命中的战术行（⑤ 保底链路内）。 */
    COMBO("熟记连招"),
    /** 战术层（TacticalPlannerImpl 单步展开）——v6 执行器 ⑤ 的保底链路。 */
    TACTICAL("经验规则"),
    /** 机械兜底：取第一个候选 / 直接结束回合。 */
    FALLBACK("兜底"),
    /** v6 执行器 ②：按计划的当前步骤执行。 */
    PLAN_STEP("照计划"),
    /** v6 执行器 ③：命中计划里的分支预案。 */
    PLAN_BRANCH("走预案"),
    /** v6 执行器 ④：临时叫一次大模型，就地看着候选选一个。 */
    LLM_CALL("决策器判断");

    private final String label;

    PathKind(String label) {
        this.label = label;
    }

    /** 中文短标签（日志 / 前端徽标）。 */
    public String label() {
        return label;
    }

    /** 按枚举名找标签：trace 里存的是 {@code name()}，回放/分析时用它翻译。 */
    public static String labelOf(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        for (PathKind kind : values()) {
            if (kind.name().equals(name)) {
                return kind.label();
            }
        }
        return name; // 旧 trace 里的历史名（REFLEX / LLM_ONESHOT）原样展示
    }
}
