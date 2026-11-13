package com.github.coldgust.ygoagent.llm.service;

import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 叙事任务（赛后复盘、连招摘要）的 AiService：String 返回（Markdown 无 schema，
 * 框架不注入格式指令）。system 提示按任务不同（复盘/连招），由组装方经 builder
 * {@code systemMessage} 注入，提示词常量留在 server 域。档位参数 bake 在模型上。
 */
public interface NarrativeAi {

    @UserMessage("{{prompt}}")
    String write(@V("prompt") String prompt);
}
