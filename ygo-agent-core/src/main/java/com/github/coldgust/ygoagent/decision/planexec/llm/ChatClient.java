package com.github.coldgust.ygoagent.decision.planexec.llm;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 最小聊天客户端接口（规划器持续 session 与执行器 one-shot 共用）。
 * <p>抽象成接口以便单测注入假实现；实现为 OpenAI 兼容 /chat/completions。
 */
public interface ChatClient {

    record Msg(String role, String content) {
        public static Msg system(String content) {
            return new Msg("system", content);
        }

        public static Msg user(String content) {
            return new Msg("user", content);
        }

        public static Msg assistant(String content) {
            return new Msg("assistant", content);
        }
    }

    /**
     * @param reasoning 思考模式：{@code "off"} 关闭（最快、JSON 最稳）、{@code "low"} 轻度思考、
     *                  {@code null} 交给服务端默认。推理型模型默认开着思考，会把 max_tokens
     *                  全花在思维链上（实测 finish_reason=length、正文为空），决策类调用必须显式关掉。
     */
    record ChatOptions(double temperature, int maxTokens, boolean jsonMode, Duration timeout,
                       String reasoning) {

        /**
         * 带思考的规划档。实测 deepseek-flash 上"低强度思考"会把 4000 tokens 全烧在推理上
         * （27.9s、finish_reason=length、正文为空），因此默认路径改用 {@link #fastPlan}；
         * 换模型（支持 reasoning 且不烧穿额度）时可切回这里。
         */
        public static ChatOptions planning(int maxTokens, Duration timeout) {
            return new ChatOptions(0.4, Math.max(maxTokens, 4_000), true, timeout, "low");
        }

        /**
         * 快档：关掉思考（~2s 出结果）。
         * {@code LLM_REASONING_PLANNER=off} 时全部规划 checkpoint 走这里；未配置时
         * checkpoint 统一走 {@link #deepPlan}，锚点也接受"首动作可能等不到计划"的代价换质量。
         */
        public static ChatOptions fastPlan(int maxTokens, Duration timeout) {
            return new ChatOptions(0.3, Math.max(maxTokens, 1_200), true, timeout, "off");
        }

        /**
         * 深度规划档：带推理链（thinking=low），但必须给足 max_tokens——
         * 实测 deepseek 把思维链也计入 max_tokens：给 4000 会 28s 后返回空正文，
         * 给 8192 则 30s 返回 4089 tokens（其中正文 ~800 字）。
         * 结论：这个档用于**全部规划器 checkpoint（锚点/增量修订/紧急重规划）**。
         * 注意锚点在首动作的等待窗口里，low 档实测 7–8s+，窗口（默认 7s）经常等不到
         * 计划——这是有意用质量换及时性；要旧行为就 {@code LLM_REASONING_PLANNER=off}。
         */
        public static ChatOptions deepPlan(int maxTokens, Duration timeout) {
            return new ChatOptions(0.4, Math.max(maxTokens, 8_000), true, timeout, "low");
        }

        public static ChatOptions decision(int maxTokens, Duration timeout) {
            // 从候选里挑一个：关掉思考，2–4 秒内必须给出 JSON
            return new ChatOptions(0.2, maxTokens, true, timeout, "off");
        }

        /**
         * 叙事档（赛后复盘、对局总结这类"写一段话"的任务）。
         *
         * <p>和决策档的三点不同：不要 JSON 模式（输出是 Markdown）、温度略高（允许措辞变化，
         * 但仍在 0.3 以内以免自由发挥成小说）、不需要 tools。这条路不在决策热路径上，
         * 超时可以给得比 one-shot 宽。
         */
        public static ChatOptions narrative(int maxTokens, Duration timeout) {
            return new ChatOptions(0.3, Math.max(maxTokens, 800), false, timeout, "off");
        }

        /** 去掉思考参数（端点不认识 thinking/reasoning_effort 时的退路）。 */
        public ChatOptions withoutReasoning() {
            return new ChatOptions(temperature, maxTokens, jsonMode, timeout, null);
        }

        /**
         * 用配置项覆盖思考强度（{@code LLM_REASONING_PLANNER} / {@code LLM_REASONING_DECISION}）。
         *
         * <p>两点注意：
         * <ul>
         *   <li>override 为 null/空白时原样返回——配置不写就等于"沿用调用档位的默认值"；</li>
         *   <li>一旦开启思考（不是 {@code "off"}），把 max_tokens 抬到至少 4000：
         *       deepseek 把思维链算进 max_tokens，给少了会 finish_reason=length、正文为空。
         *       这是实测过的坑（{@link #planning} 的注释里也记了同一条）。</li>
         * </ul>
         */
        public ChatOptions withReasoning(String override) {
            String level = override == null ? "" : override.trim();
            if (level.isEmpty() || level.equalsIgnoreCase(reasoning)) {
                return this;
            }
            int tokens = "off".equalsIgnoreCase(level) ? maxTokens : Math.max(maxTokens, 4_000);
            return new ChatOptions(temperature, tokens, jsonMode, timeout, level.toLowerCase());
        }
    }

    /**
     * 发起一次补全；失败抛 {@link ChatException}。
     */
    String complete(List<Msg> messages, ChatOptions options);

    /* ---------- 工具调用（OpenAI tools / function calling） ---------- */

    /**
     * 提供给模型的工具（OpenAI tools 规范）。
     *
     * @param name        工具名（模型按名字调用）
     * @param description 何时该用的说明——这段文字直接决定模型会不会用、用得对不对
     * @param parameters  JSON Schema 形式的参数定义
     */
    record Tool(String name, String description, Map<String, Object> parameters) {
    }

    /** 模型发起的一次工具调用（arguments 是 JSON 字符串）。 */
    record ToolCall(String id, String name, String arguments) {
    }

    /** 工具执行器：拿到调用，返回给模型看的文本结果。 */
    @FunctionalInterface
    interface ToolHandler {
        String execute(ToolCall call);
    }

    /**
     * 带工具的补全：模型可以在给出最终答案前调用工具，拿到结果后继续推理。
     * <p>默认实现忽略工具、退化为普通补全——老的实现与测试桩因此无需改动；
     * 只有真正支持 function calling 的实现（覆写本方法）才会走工具回路。
     */
    default String completeWithTools(List<Msg> messages, ChatOptions options,
                                     List<Tool> tools, ToolHandler handler) {
        return complete(messages, options);
    }

    /** 调用失败（网络/超时/非 2xx/响应不可解析）。 */
    class ChatException extends RuntimeException {
        public ChatException(String message) {
            super(message);
        }

        public ChatException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
