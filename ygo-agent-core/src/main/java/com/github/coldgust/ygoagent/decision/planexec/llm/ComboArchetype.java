package com.github.coldgust.ygoagent.decision.planexec.llm;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 卡组主题（archetype）的归一化与别名。
 */
public final class ComboArchetype {

    /** 别名 → 编译库代码。键必须是 normalized 形式（大写、下划线）。 */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("SKY_STRIKER", "SKY_STRIKER"),
            Map.entry("SKYSTRIKER", "SKY_STRIKER"),
            Map.entry("闪刀", "SKY_STRIKER"),
            Map.entry("闪刀姬", "SKY_STRIKER"),
            Map.entry("閃刀姫", "SKY_STRIKER"));

    private ComboArchetype() {
    }

    public static String canonical(String raw) {
        String key = normalize(raw);
        if (key.isEmpty()) {
            return "";
        }
        return ALIASES.getOrDefault(key, key);
    }

    public static boolean matches(String a, String b) {
        String left = canonical(a);
        String right = canonical(b);
        return !left.isEmpty() && left.equals(right);
    }

    /**
     * 一个系列的候选文件名（编译库按代码命名；老数据可能按中文命名）。
     * 顺序：归一化代码优先，原始写法兜底。
     */
    public static List<String> candidates(String raw) {
        String canon = canonical(raw);
        String normalized = normalize(raw);
        if (canon.isEmpty()) {
            return List.of();
        }
        return normalized.isEmpty() || normalized.equals(canon)
                ? List.of(canon)
                : List.of(canon, normalized);
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_')
                .replace("　", "");
    }
}
