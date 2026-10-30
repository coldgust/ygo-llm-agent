package com.github.coldgust.ygoagent.decision.planexec.llm;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 卡组主题（archetype）的归一化与别名。
 *
 * <p>为什么需要：同一个系列有<b>三套写法</b>，它们分别出现在不同地方——
 * 编译库用代码（{@code data/combos/compiled/SKY_STRIKER.json}）、
 * 前端下拉与录入用中文（{@code 闪刀}）、模型自己会写 {@code Sky Striker}。
 * 不做归一化，就会出现"录入了 combo 但一条都查不到"，
 * 甚至更糟：按 {@code 闪刀.json} 找不到编译库 → 连招索引整个关闭（实机日志：
 * {@code combo 编译库不存在（data/combos/compiled/闪刀.json），连招索引关闭}），
 * {@code find_combos} 也不会注册，模型看到的就是"这个 deck 没有 combo"。
 *
 * <p>归一化规则：去空白、大小写不敏感、空格/连字符/下划线视为等价；
 * 再按别名表收敛到编译库使用的代码。别名表只收"同一系列的常见写法"，
 * 新增系列时在这里加一行即可。
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

    /** 归一化成编译库那种代码；空串原样返回空串。 */
    public static String canonical(String raw) {
        String key = normalize(raw);
        if (key.isEmpty()) {
            return "";
        }
        return ALIASES.getOrDefault(key, key);
    }

    /** 两个写法是否指同一个系列。 */
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
