package com.github.coldgust.ygoplayserver.duel.combo;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class ComboSummaryPrompts {

    static final String SYSTEM = """
            你是游戏王（Yu-Gi-Oh!）大师规则的连招分析师。用户会给你一段实机对局中某玩家回合的
            逐步操作记录（已翻译成自然语言，含卡名、区域与表示形式）。你的任务是把它整理成一份
            简明的连招摘要，供后续对局 agent 在使用同一卡组时参考。

            铁律：
            1. 只描述记录里实际发生的操作，绝不推测"可能还做了什么"。记录是感知受限的
               （看不到手牌、看不到部分效果细节），缺信息就明说"记录未显示"，不许编造。
            2. 卡名以记录为准，不要改写或"纠正"。
            3. 用中文，总长不超过 300 字，不使用 Markdown 标记。

            输出格式（五行，行首用固定标签）：
            概述：这套连招在做什么（一句话）
            展开：关键步骤串（按顺序，挑重要的，别逐条流水账）
            终端：回合结束时达成的场面/收益
            关键卡牌：这套连招的核心卡名，3–6 张，用顿号（、）分隔；只写记录里实际出现过的卡名
            备注：起手条件、风险或记录局限（没有就写"无"）""";

    static String user(String archetype, List<String> stepDescriptions) {
        StringBuilder sb = new StringBuilder();
        sb.append("卡组主题：").append(archetype == null || archetype.isBlank() ? "未归类" : archetype)
                .append('\n');
        sb.append("该玩家回合的逐步操作记录（按时间顺序）：\n");
        int n = 1;
        for (String step : stepDescriptions) {
            sb.append(n++).append(". ").append(step).append('\n');
        }
        sb.append("\n请按系统提示的五行格式输出摘要（关键卡牌行必须给，实在没有就写“无”）。");
        return sb.toString();
    }

    static boolean looksUsable(String text) {
        return text != null && text.length() >= 40
                && text.contains("概述") && text.contains("终端");
    }

    static String keyCardsOf(String text) {
        if (text == null) {
            return null;
        }
        for (String line : text.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("关键卡牌")) {
                int colon = trimmed.indexOf('：');
                if (colon < 0) {
                    colon = trimmed.indexOf(':');
                }
                if (colon < 0) {
                    return null;
                }
                String body = trimmed.substring(colon + 1).trim();
                if (body.isEmpty() || body.contains("无")) {
                    return null;
                }
                String joined = Arrays.stream(body.split("[、,，]"))
                        .map(String::trim)
                        .filter(v -> !v.isEmpty())
                        .distinct()
                        .limit(8)
                        .collect(Collectors.joining(","));
                return joined.isEmpty() ? null : joined;
            }
        }
        return null;
    }

    private ComboSummaryPrompts() {
    }
}
