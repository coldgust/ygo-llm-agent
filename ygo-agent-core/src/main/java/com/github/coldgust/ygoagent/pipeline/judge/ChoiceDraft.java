package com.github.coldgust.ygoagent.pipeline.judge;

import com.github.coldgust.ygoagent.model.CandidateId;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoagent.pipeline.plan.ZoneCodes;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规划器抢答 / 决策器判断（④）的选择草案（候选 id + 理由 + 置信度）。
 *
 * <p>{@code zone}：如果这次选的是会落到场上的动作（召唤/特召/盖放/发动），
 * 模型可以在同一次回答里一并给出"放到哪一格"。位置是决策的一部分，
 * 但<b>不为它单独发一次请求</b>——没给或给得不合法就按"第一个可放"退。
 */
public record ChoiceDraft(CandidateId choice, String rationale, double confidence,
                          ZoneRef zone) {

    private static final Pattern CHOICE_PATTERN =
            Pattern.compile("\"choice\"\\s*:\\s*\"?(C\\d+)\"?");
    private static final Pattern CONFIDENCE = Pattern.compile("\"confidence\"\\s*:\\s*([0-9.]+)");

    public ChoiceDraft(CandidateId choice, String rationale, double confidence) {
        this(choice, rationale, confidence, null);
    }

    /** 从模型原始输出解析草案；解析不了（空/无 choice/数字非法）返回空。 */
    public static Optional<ChoiceDraft> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Matcher choice = CHOICE_PATTERN.matcher(raw);
        if (!choice.find()) {
            return Optional.empty();
        }
        try {
            CandidateId id = CandidateId.of(Integer.parseInt(choice.group(1).substring(1)));
            Matcher conf = CONFIDENCE.matcher(raw);
            double confidence = conf.find() ? Double.parseDouble(conf.group(1)) : 0.5;
            String rationale = extractField(raw, "rationale");
            // 位置与选择同一次给出；解析不了就当没给（不追问、不额外发请求）
            ZoneRef zone =
                    ZoneCodes
                            .parse(extractField(raw, "zone"),
                                    PlayerSide.SELF)
                            .orElse(null);
            return Optional.of(new ChoiceDraft(id, rationale,
                    Math.max(0, Math.min(1, confidence)), zone));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String extractField(String raw, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(raw);
        return m.find() ? m.group(1) : "";
    }
}
