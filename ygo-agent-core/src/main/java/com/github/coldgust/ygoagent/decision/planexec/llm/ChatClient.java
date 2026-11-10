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

    record ChatOptions(double temperature, int maxTokens, boolean jsonMode, Duration timeout,
                       String reasoning) {

        public static ChatOptions planning(int maxTokens, Duration timeout) {
            return new ChatOptions(0.4, Math.max(maxTokens, 4_000), true, timeout, "low");
        }

        public static ChatOptions fastPlan(int maxTokens, Duration timeout) {
            return new ChatOptions(0.3, Math.max(maxTokens, 1_200), true, timeout, "off");
        }

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

        public ChatOptions withReasoning(String override) {
            String level = override == null ? "" : override.trim();
            if (level.isEmpty() || level.equalsIgnoreCase(reasoning)) {
                return this;
            }
            int tokens = "off".equalsIgnoreCase(level) ? maxTokens : Math.max(maxTokens, 4_000);
            return new ChatOptions(temperature, tokens, jsonMode, timeout, level.toLowerCase());
        }
    }

    String complete(List<Msg> messages, ChatOptions options);


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

    class ChatException extends RuntimeException {
        public ChatException(String message) {
            super(message);
        }

        public ChatException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
