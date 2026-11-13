package com.github.coldgust.ygoagent.llm;

/**
 * LLM 调用失败（HTTP 状态 / 超时 / 网络抖动）。消息文案是重试分类与日志的依据：
 * 含 timeout/超时 = 超时类（不重试），HTTP 400 = 端点拒绝参数（丢思考参数重试）。
 */
public class LlmChatException extends RuntimeException {

    public LlmChatException(String message) {
        super(message);
    }

    public LlmChatException(String message, Throwable cause) {
        super(message, cause);
    }
}
