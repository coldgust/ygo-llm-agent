package com.github.coldgust.ygoagent.knowledge;

/**
 * [实战连招参考] 文本渲染：按主题取最近的真实对局录入拼成上下文段。
 * 从 DuelAgent 门面归位到 knowledge（与 combo 数据源同包）；无可用录入时返回 null（注入段缺席）。
 */
public final class ComboReferenceText {

    private ComboReferenceText() {
    }

    public static String render(RecordedComboSource recorded, String archetype, int comboContextLimit) {
        if (recorded == null || !recorded.available()
                || archetype == null || archetype.isBlank()) {
            return null;
        }
        var entries = recorded.recentByArchetype(archetype, comboContextLimit);
        if (entries.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (var entry : entries) {
            sb.append("#").append(n++).append("（").append(entry.stepCount()).append(" 步）");
            if (!entry.keyCards().isEmpty()) {
                sb.append(" 关键卡牌：").append(String.join("、", entry.keyCards()));
            }
            sb.append("\n");
            if (entry.summary() != null && !entry.summary().isBlank()) {
                sb.append(entry.summary()).append("\n");
            }
            var steps = entry.stepDescriptions();
            if (steps.isEmpty()) {
                continue;
            }
            sb.append("实际操作：");
            for (int i = 0; i < Math.min(6, steps.size()); i++) {
                sb.append(i == 0 ? "" : " → ").append(steps.get(i));
            }
            if (steps.size() > 6) {
                sb.append(" → …（共 ").append(steps.size()).append(" 步）");
            }
            sb.append("\n\n");
        }
        return sb.toString().trim();
    }
}
