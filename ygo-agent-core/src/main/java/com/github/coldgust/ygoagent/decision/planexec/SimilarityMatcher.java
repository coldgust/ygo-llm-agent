package com.github.coldgust.ygoagent.decision.planexec;

import com.github.coldgust.ygoagent.decision.model.DecisionPoint;
import com.github.coldgust.ygoagent.decision.planexec.planner.TurnPlan;

import com.github.coldgust.ygodomain.duel.PlayerSide;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;

/**
 * ③ 相似匹配（v6 §2.2.4）：分支 assumes（封闭词表）× 当前局面特征 → 加权相似度。
 * <p>全部确定性计算；"高度相似"在这里有唯一可计算的定义。
 */
public final class SimilarityMatcher {

    public record Match(TurnPlan.TurnBranch branch, double score) {
    }

    /** 当前局面的特征向量（封闭词表，v6 §3.1）。 */
    public static Map<String, Object> features(DecisionPoint point) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("phase", normalizePhase(point.kind().name()));
        f.put("opp_monsters", point.state().monsters(PlayerSide.OPPONENT).size());
        f.put("opp_backrow", point.state().spellTraps(PlayerSide.OPPONENT).size());
        f.put("hand_bucket", handBucket(point.state().opponent().handCount()));
        f.put("lp_bucket", lpBucket(point.state().opponent().lifePoints()));
        return f;
    }

    /** 从局面提取全部已知 passcode（key_cards 命中判定用）。 */
    private static List<Long> knownPasscodes(DecisionPoint point) {
        List<Long> out = new ArrayList<>();
        point.state().cards().values().forEach(c -> {
            if (!c.isUnknown() && c.passcode() > 0) {
                out.add(c.passcode());
            }
        });
        point.state().players().values().forEach(p -> {
            p.graveyard().forEach(id -> out.add(id.passcode()));
            p.banished().forEach(id -> out.add(id.passcode()));
        });
        return out;
    }

    /** 最佳分支：相似度 ≥ 阈值中取顺序最靠前者（规划器生成顺序即优先级）。 */
    public Optional<Match> bestBranch(DecisionPoint point, TurnPlan plan, double threshold) {
        if (plan == null || plan.branches().isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> features = features(point);
        List<Long> passcodes = knownPasscodes(point);
        for (TurnPlan.TurnBranch branch : plan.branches()) {
            double score = score(branch.assumes(), features, passcodes);
            if (score >= threshold) {
                return Optional.of(new Match(branch, score));
            }
        }
        return Optional.empty();
    }

    /** 相似度 = 命中特征数 / 总特征数（description 为文本说明，不参与打分）。 */
    public static double score(Map<String, Object> assumes, Map<String, Object> features, List<Long> passcodes) {
        if (assumes == null || assumes.isEmpty()) {
            return 0.0;
        }
        int total = 0;
        int matched = 0;
        for (Map.Entry<String, Object> entry : assumes.entrySet()) {
            String key = entry.getKey();
            Object expected = entry.getValue();
            if (key.equals("description") || expected == null) {
                continue;
            }
            total++;
            if (matches(key, expected, features, passcodes)) {
                matched++;
            }
        }
        return total == 0 ? 0.0 : (double) matched / total;
    }

    private static boolean matches(String key, Object expected, Map<String, Object> features, List<Long> passcodes) {
        if (key.equals("key_cards")) {
            List<Long> wanted = new ArrayList<>();
            if (expected instanceof List<?> list) {
                list.forEach(v -> wanted.add(Long.parseLong(String.valueOf(v))));
            } else {
                wanted.add(Long.parseLong(String.valueOf(expected)));
            }
            if (wanted.isEmpty()) {
                return true;
            }
            long hit = wanted.stream().filter(passcodes::contains).distinct().count();
            return hit >= Math.max(1, wanted.size() / 2); // 过半在场/在墓即算命中
        }
        String expectedText = String.valueOf(expected);
        Object actual = features.get(key);
        if (actual == null) {
            return false;
        }
        if (key.equals("phase")) {
            return normalizePhase(expectedText).equals(normalizePhase(String.valueOf(actual)));
        }
        // 数量约束：<=n / >=n / ==n / n
        if (actual instanceof Number number) {
            int value = number.intValue();
            String t = expectedText.trim();
            try {
                if (t.startsWith("<=")) return value <= Integer.parseInt(t.substring(2).trim());
                if (t.startsWith(">=")) return value >= Integer.parseInt(t.substring(2).trim());
                if (t.startsWith("==")) return value == Integer.parseInt(t.substring(2).trim());
                if (t.startsWith("<")) return value < Integer.parseInt(t.substring(1).trim());
                if (t.startsWith(">")) return value > Integer.parseInt(t.substring(1).trim());
                return value == Integer.parseInt(t);
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return expectedText.equals(String.valueOf(actual));
    }

    private static String normalizePhase(String phase) {
        return switch (phase.toUpperCase(Locale.ROOT)) {
            case "MAIN1", "MAIN", "MAIN_PHASE", "MAIN2" -> "MAIN_PHASE";
            case "BATTLE", "BP", "BATTLE_PHASE" -> "BATTLE_PHASE";
            default -> phase.toUpperCase(Locale.ROOT);
        };
    }

    private static String handBucket(int handCount) {
        if (handCount <= 1) return "0-1";
        if (handCount <= 3) return "2-3";
        if (handCount <= 5) return "4-5";
        return "6+";
    }

    private static String lpBucket(int lp) {
        if (lp > 6000) return ">6000";
        if (lp > 3000) return "3000-6000";
        return "<=3000";
    }

    public SimilarityMatcher() {
    }
}
