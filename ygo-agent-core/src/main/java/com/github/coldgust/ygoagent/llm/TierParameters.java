package com.github.coldgust.ygoagent.llm;

import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;

import java.util.Map;

/**
 * 档位 → wire 参数的唯一映射（deepseek 私有参数也在这里）：
 * <ul>
 *   <li>json mode 只用于不带工具的调用：json_object 会非确定性抑制 deepseek 的结构化
 *       tool_calls（实测见 pitfalls §7），带工具调用靠框架追加的 schema 指令保证 JSON 遵循度。
 *       {@code withTools} 由组装方按"工具列表是否为空"派生，不是调用点可见的概念；</li>
 *   <li>思考档 off → {@code reasoning_effort=none} + {@code thinking.type=disabled}（deepseek 私有
 *       {@code thinking} 走 customParameters 拍平进请求体顶层），其余档位 enabled + effort。</li>
 * </ul>
 */
public final class TierParameters {

    public static OpenAiChatRequestParameters of(ChatOptions options, String model, boolean withTools) {
        OpenAiChatRequestParameters.Builder builder = OpenAiChatRequestParameters.builder()
                .modelName(model)
                .temperature(options.temperature())
                .maxOutputTokens(options.maxTokens());
        if (options.jsonMode() && !withTools) {
            builder.responseFormat(ResponseFormat.JSON);
        }
        if (options.reasoning() != null) {
            if ("off".equals(options.reasoning())) {
                builder.reasoningEffort("none")
                        .customParameters(Map.of("thinking", Map.of("type", "disabled")));
            } else {
                builder.reasoningEffort(options.reasoning())
                        .customParameters(Map.of("thinking", Map.of("type", "enabled")));
            }
        }
        return builder.build();
    }

    private TierParameters() {
    }
}
