package com.github.coldgust.ygoagent.decision.planexec.llm;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * LLM 思考强度（reasoning effort）的进程级配置。
 *
 * <p>为什么要做成配置项：思考强度直接决定"想得多深 / 多快出结果"，而这两个目标天然冲突——
 * 规划器需要深度，决策器需要速度。写死在代码里就只能改代码重编译；放到 env 里，
 * 换模型、换机器、临时排查都能立刻生效（改完重启进程即可）。
 *
 * 取值 {@code off | low | medium | high}，留空 = 沿用各调用档位的默认值
 * （现状：规划器 checkpoint 统一 {@code low}、决策 {@code off}；{@code off} 让规划 checkpoint
 * 走快档——锚点快出计划的旧行为）。
 *
 * <p>两个键，分别对应两条调用路径：
 * <ul>
 *   <li>{@code LLM_REASONING_PLANNER}：规划器（锚点首份计划、events checkpoint、紧急重规划）；</li>
 *   <li>{@code LLM_REASONING_DECISION}：执行器 one-shot 决策器判断与规划器抢答（候选里挑一个）。</li>
 * </ul>
 *
 * <p>两个已知代价，写在这里免得踩第二次：
 * <ul>
 *   <li>思考链会算进 max_tokens，给少了正文为空——{@link ChatClient.ChatOptions#withReasoning}
 *       在开启思考时会把预算抬到 4000；</li>
 *   <li>决策路径有硬超时（{@code ONESHOT_TIMEOUT_MS}，默认 5s）。给决策开 medium/high
 *       基本等于每次决策都超时降级，除非同时把超时和等待预算调大。</li>
 * </ul>
 */
public final class LlmTuning {

    /** 规划器思考强度。 */
    public static final String PLANNER_KEY = "LLM_REASONING_PLANNER";
    /** 决策器思考强度。 */
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

    /** 便于测试/CLI 显式传入（不读环境）。 */
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

    /** 规划器档位；null = 用调用档位的默认值。 */
    public String planner() {
        return planner;
    }

    /** 决策器档位；null = 用调用档位的默认值。 */
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
