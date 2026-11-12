package com.github.coldgust.ygoagent.model;

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
