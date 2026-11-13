package com.github.coldgust.ygoagent.pipeline.judge;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.github.coldgust.ygoagent.model.CandidateId;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoagent.pipeline.plan.ZoneCodes;

/**
 * 决策器判断（④）的结构化输出（框架 POJO 解析，替代旧 ChoiceDraft 正则）。
 *
 * <p>{@code zone}：如果这次选的是会落到场上的动作（召唤/特召/盖放/发动），
 * 模型可以在同一次回答里一并给出"放到哪一格"（MMZ-3 这类格子编码）。
 * 位置是决策的一部分，但<b>不为它单独发一次请求</b>——没给或给得不合法就按"第一个可放"退。
 */
public record JudgeAnswer(String choice, Double confidence, String rationale,
                          @JsonAlias("zoneCode") String zone) {

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
        return Math.max(0, Math.min(1, v));
    }

    /** 位置与选择同一次给出；解析不了就当没给（不追问、不额外发请求）。 */
    public ZoneRef zoneRef() {
        return ZoneCodes.parse(zone, PlayerSide.SELF).orElse(null);
    }
}
