package com.github.coldgust.ygoagent.pipeline.judge;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.coldgust.ygoagent.model.CandidateId;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoagent.pipeline.plan.ZoneCodes;
import dev.langchain4j.model.output.structured.Description;

/**
 * 决策器判断（④）的结构化输出（框架 POJO 解析，替代旧 ChoiceDraft 正则）。
 *
 * <p><b>输出契约唯一源 = 本 POJO 的 {@code @Description} 注解</b>（2026-11-14 用户指令：
 * 契约用 LangChain4j 注解实现，不写在提示词里）——框架从注解生成格式指令追加到每条请求，
 * {@link JudgePrompts} 的 system 只保留行为纪律，不重复字段语义。
 * {@code JudgePromptsContractTest} 用反射钉住"每个组件都有 @Description"。
 *
 * <p>{@code zone}：如果这次选的是会落到场上的动作（召唤/特召/盖放/发动），
 * 模型可以在同一次回答里一并给出"放到哪一格"（按 [场地槽位] 的写法，如 {@code 我方主怪兽区3}；
 * 旧代号 {@code MMZ-3} 仍能解析，兼容模型的旧习惯）。
 * 位置是决策的一部分，但<b>不为它单独发一次请求</b>——没给或给得不合法就按"第一个可放"退。
 *
 * <p>{@code needReplan}/{@code replanReason}：决策器对本回合规划的判决与理由。
 * true 会触发规划器立即重规划（旧建议作废、新规划发布前决策器不带旧规划）；
 * 本手决策不受影响——choice 仍是必答项。契约语义（何时 true、原因写什么）全部在注解里。
 *
 * <p>{@code @JsonIgnoreProperties}：模型可能输出 POJO 之外的键（实机 2026-11-14：提示词要求
 * 的 verify 字段不在 POJO 里，严格解析把每手决策打成"输出不可解析"）——未知键一律忽略。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JudgeAnswer(
        @Description("决策结果：必须是 [候选] 中真实存在的编号（如 C1）。弃权类动作在「弃权项：」行、"
                + "阶段动作在「阶段动作：」行，都带编号；本请求没列出的动作就是做不了的。")
        String choice,
        @Description("置信度 0.0–1.0。")
        Double confidence,
        @Description("关键理由，≤40 字，与 choice 指向同一件事。")
        String rationale,
        @Description("仅当这一步的动作会落场时给出区域位置（按 [场地槽位] 的写法，如 我方主怪兽区3）；"
                + "说不出就省略，系统取第一个可放置区域。")
        @JsonAlias("zoneCode") String zone,
        @Description("对当前 [规划器情报] 所载规划的判决：规划已不可能实现、已不合理、"
                + "或已与最新局面偏离时 true；规划仍适用则省略或 false。"
                + "true 会触发规划器立即重规划（旧建议作废），但本手决策不受影响——choice 仍是必答项。")
        Boolean needReplan,
        @Description("needReplan=true 时必填：判决原因（≤60 字，写清哪条线已落空、哪个前提已被破坏、"
                + "或局面偏离在哪），将原样转达给规划器重规划。needReplan 非 true 时省略。")
        @JsonAlias({"replan_reason", "replanReason"}) String replanReason) {

    /** 解析候选编号：接受 "C3"/"3"；解析不了返回 null（上层按越界/弃权处理）。 */
    public CandidateId candidateId() {
        if (choice == null) {
            return null;
        }
        String digits = choice.trim().toUpperCase();
        if (digits.startsWith("C")) {
            digits = digits.substring(1);
        }
        try {
            return CandidateId.of(Integer.parseInt(digits.trim()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 置信度（缺省 0.5，clamp 0–1）。 */
    public double confidenceOr(double fallback) {
        double v = confidence == null ? fallback : confidence;
        return Math.clamp(v, 0, 1);
    }

    /** 重规划判决（缺省 false；模型可能省略该键）。 */
    public boolean needReplanOr(boolean fallback) {
        return needReplan == null ? fallback : needReplan;
    }

    /** 位置与选择同一次给出；解析不了就当没给（不追问、不额外发请求）。 */
    public ZoneRef zoneRef() {
        return ZoneCodes.parse(zone, PlayerSide.SELF).orElse(null);
    }
}
