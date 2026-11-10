package com.github.coldgust.ygoagent.decision.planexec.llm;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * LLM 思考强度（reasoning effort）的进程级配置。
 */
public final class LlmTuning {

    public static final String PLANNER_KEY = "LLM_REASONING_PLANNER";
    public static final String DECISION_KEY = "LLM_REASONING_DECISION";

    private static final Set<String> LEVELS = Set.of("off", "low", "medium", "high");

    /** 进程级单例：默认读真实环境变量；.env 文件的值经 {@link #init} 在启动时并入。 */
    private static volatile LlmTuning instance = new LlmTuning(
            normalize(System.getenv(PLANNER_KEY)), normalize(System.getenv(DECISION_KEY)));

    private final String planner;
    private final String decision;

    private LlmTuning(String planner, String decision) {
        this.planner = planner;
        this.decision = decision;
    }

    public static LlmTuning get() {
        return instance;
    }

    public static LlmTuning of(Map<String, String> env) {
        return new LlmTuning(normalize(env == null ? null : env.get(PLANNER_KEY)),
                normalize(env == null ? null : env.get(DECISION_KEY)));
    }

    /**
     * 启动时用合并 env 显式初始化。为什么要 init：本类默认只读 System.getenv，而 .env 文件
     * 的值只进了 RuntimeConfig/resolver 的合并 Map——不走这里，.env 里的思考强度会静默失效。
     * 真实环境变量优先（与 RuntimeConfig.resolveLlm 同规则）。
     */
    public static void init(Map<String, String> env) {
        instance = new LlmTuning(
                normalize(firstNonBlank(System.getenv(PLANNER_KEY), env.get(PLANNER_KEY))),
                normalize(firstNonBlank(System.getenv(DECISION_KEY), env.get(DECISION_KEY))));
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    public String planner() {
        return planner;
    }

    public String decision() {
        return decision;
    }

    /**
     * 归一化：只接受 {@code off/low/medium/high}（大小写不敏感）。
     *
     * <p>写错时返回 null（= 不覆盖）而不是抛异常：这是"调参"不是"启动依赖"，
     * 拼错一个词不该让整局跑不起来。
     */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String level = raw.trim().toLowerCase(Locale.ROOT);
        return LEVELS.contains(level) ? level : null;
    }
}
