package com.github.coldgust.ygoagent.pipeline.plan;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 规划器 checkpoint 的结构化输出（框架 POJO 解析，替代旧 PlanTreeParser 的 JsonNode 提取）。
 *
 * <p>两种输出形态共用一个 POJO（与 PlannerPrompts 的两套 system 契约对应）：
 * <ul>
 *   <li>路径规划模式：{@code thinking / goal / main_line{steps,branches}}；</li>
 *   <li>顾问模式（默认）：{@code analysis / resources / goal / options / tactics / combo}，
 *       由 {@code PlanAssembler} 拼成 thinking，下游无需区分两种形态。</li>
 * </ul>
 * {@code keep=true} 表示计划有效无需修改；字段缺失（null）与空数组在语义上不同：
 * null = 未按 schema 输出，空数组 = 显式的"本回合按住不动"。
 */
public record PlanAnswer(
        Boolean keep,
        Double confidence,
        String thinking,
        String goal,
        @JsonProperty("main_line") @JsonAlias("mainLine") MainLine mainLine,
        String analysis,
        String resources,
        List<OptionDraft> options,
        String tactics,
        String combo,
        @JsonProperty("plan") @JsonAlias({"data", "result"}) PlanAnswer plan) {

    /** 模型偶尔套一层 {"plan": {...}} 外壳：组装前先解包。 */
    public PlanAnswer unwrap() {
        return plan != null ? plan : this;
    }

    public record MainLine(List<StepDraft> steps, List<BranchDraft> branches) {
    }

    /** {@code zone} 接受格子编码字符串（"MMZ-3"），也容忍对象写法（{"kind":"mainMonster","sequence":3}）。 */
    public record StepDraft(String verb, Long passcode, Object zone, String when, String why) {
    }

    /** {@code assumes} 在提示词里是 {@code "if"} 对象（键：phase/opp_monsters/key_cards/description…）。 */
    public record BranchDraft(String name,
                              @JsonProperty("if") @JsonAlias({"assumes", "when"}) Map<String, Object> assumes,
                              List<StepDraft> steps) {
    }

    /**
     * 顾问模式的候选方案比较；{@code line} 是动作序列数组（"检索X" → "用X" → …），
     * 但模型偶尔写成单个字符串——{@code @JsonCreator} 收编两态。
     */
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
        return Math.max(0, Math.min(1, v));
    }
}
