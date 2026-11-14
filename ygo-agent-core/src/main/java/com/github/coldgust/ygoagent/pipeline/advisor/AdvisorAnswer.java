package com.github.coldgust.ygoagent.pipeline.advisor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.model.output.structured.Description;

import java.util.List;

/**
 * 规划器（战术幕僚）checkpoint 的结构化输出（docs/planner-judge-memory-redesign.md §6.2）。
 * 替代路径规划的 {@code PlanAnswer}：没有 passcode/zone/verb 结构化字段，
 * 不做接地校验。<b>只有全量建议一种输出形态</b>（keep 已随回合内主动修订一起废止，
 * 2026-11-14 用户指令：每回合锚点规划一次 + 决策器判决重规划，每次都必须产出完整建议）。
 *
 * <p><b>输出契约唯一源 = 本 POJO 的 {@code @Description} 注解</b>（2026-11-14 用户指令：
 * 契约用 LangChain4j 注解实现，不写在提示词里）——框架从注解生成格式指令追加到每条请求，
 * {@code PlannerPrompts} 的 system 只保留行为纪律与前后一致性要求，不重复字段语义。
 * {@code AdvisorAnswerContractTest} 用反射钉住"每个组件（含嵌套 OptionDraft）都有 @Description"。
 *
 * <p>字段缺失（null）与空串在语义上不同：全部内容字段为空 = 未按 schema 输出
 * （上层判解析失败，不发布建议）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdvisorAnswer(
        @Description("置信度 0.0–1.0。")
        Double confidence,
        @Description("本局策略，≤200 字：对手卡组判断、胜负手、资源大方向。")
        String strategy,
        @Description("≤200 字，只写快照/记录里可核对的事实。")
        String analysis,
        @Description("资源上限，≤150 字：通召/特召剩余、每张可发动牌能换到什么、"
                + "墓地魔法现状→目标、剩余格子。")
        String resources,
        @Description("一句话：斩杀/压制/保资源哪一档。")
        String goal,
        @Description("2–3 条候选线（唯一合法动作才允许 1 条），每条含 line/gain/risk/pick/why；"
                + "恰好一条 pick=true。")
        List<OptionDraft> options,
        @Description("≤200 字：主打哪条、为什么比别的强、打到什么程度停、必须留下什么。")
        String tactics,
        @Description("写给决策器的现场情报，≤200 字：只写能直接改变一个选择的内容"
                + "（对手已交的阻抗、盖卡推断、值得打断的时点、关键卡面互动、资源备注），"
                + "不写场面复述。")
        String intel,
        @Description("选中线的完整动作链，≤400 字：条件 → 每步换到什么 → "
                + "打完的局面/墓地/手牌 → 下回合能否接着打。")
        String combo) {

    /** 是否给出了实质建议内容（keep 缺失且内容全空 = 输出无效）。 */
    public boolean hasAdvice() {
        return !blank(strategy) || !blank(goal) || !blank(tactics) || !blank(intel)
                || !blank(analysis) || !blank(combo);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 候选方案比较；{@code line} 是动作序列数组（"检索X" → "用X" → …），
     * 模型偶尔写成单个字符串——{@code @JsonCreator} 收编两态（沿袭 PlanAnswer.OptionDraft）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OptionDraft(
            @Description("动作序列数组（被选中的线至少 3 步；资源确实只够 1–2 步才允许更短），"
                    + "每步说清\"把什么换成什么\"，每步必须现在或上一步之后能做。")
            List<String> line,
            @Description("这条线的收益。")
            String gain,
            @Description("这条线的风险与代价：被无效会怎样、打空后剩什么。")
            String risk,
            @Description("全部选项里恰好一条 true。")
            Boolean pick,
            @Description("为什么选它/淘汰它。")
            String why) {

        @JsonCreator
        static OptionDraft of(@JsonProperty("line") Object line,
                              @JsonProperty("gain") String gain,
                              @JsonProperty("risk") String risk,
                              @JsonProperty("pick") Boolean pick,
                              @JsonProperty("why") String why) {
            List<String> lines;
            if (line instanceof List<?> l) {
                lines = l.stream().map(String::valueOf).toList();
            } else if (line != null) {
                lines = List.of(String.valueOf(line));
            } else {
                lines = null;
            }
            return new OptionDraft(lines, gain, risk, pick, why);
        }
    }

    public double confidenceOr(double fallback) {
        double v = confidence == null ? fallback : confidence;
        return Math.clamp(v, 0, 1);
    }
}
