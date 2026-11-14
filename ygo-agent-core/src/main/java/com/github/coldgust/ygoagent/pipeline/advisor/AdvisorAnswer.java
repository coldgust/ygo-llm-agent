package com.github.coldgust.ygoagent.pipeline.advisor;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 规划器（战术幕僚）checkpoint 的结构化输出（docs/planner-judge-memory-redesign.md §6.2，
 * 附录 A.1 输出契约）。替代路径规划的 {@code PlanAnswer}：没有 passcode/zone/verb 结构化字段，
 * 不做接地校验；两种输出形态（keep 或全量建议）共用一个 POJO。
 *
 * <p>{@code keep=true} 表示建议仍适用、快照不变；字段缺失（null）与空串在语义上不同：
 * 全部内容字段为空且 keep 缺失 = 未按 schema 输出（上层判解析失败）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdvisorAnswer(
        Boolean keep,
        Double confidence,
        String strategy,
        String analysis,
        String resources,
        String goal,
        List<OptionDraft> options,
        String tactics,
        String intel,
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
    public record OptionDraft(List<String> line, String gain, String risk, Boolean pick, String why) {

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
