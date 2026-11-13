package com.github.coldgust.ygoagent.llm.service;

import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 叙事任务的 AiService（包级私有，由 {@code NarratorAgent} 包装）：String 返回
 * （Markdown 无 schema，框架不注入格式指令）。
 */
interface NarrativeAi {

    @UserMessage("{{prompt}}")
    String write(@V("prompt") String prompt);
}
