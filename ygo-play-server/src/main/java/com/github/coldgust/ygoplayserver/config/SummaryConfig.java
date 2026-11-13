package com.github.coldgust.ygoplayserver.config;

import com.github.coldgust.ygoagent.support.Texts;
import com.github.coldgust.ygoagent.llm.ChatOptions;
import com.github.coldgust.ygoagent.llm.LlmEndpoint;
import com.github.coldgust.ygoagent.llm.AgentModels;
import com.github.coldgust.ygoagent.llm.service.NarratorAgent;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoplayserver.duel.DuelSummaryService;
import com.github.coldgust.ygoplayserver.duel.summary.DuelSummaryPrompts;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 赛后复盘的装配点。刻意收拢在一个工厂里，让「哪些情况下会退化」在一处看得见：
 * 缺 LLM key → 只写模板摘要；缺 LightRAG → 写入失败只告警，都不影响对局本身。
 */
@Configuration
public class SummaryConfig {

    @Bean
    public DuelSummaryService duelSummaryService(LightRagProperties lightRag,
                                                 DuelSummaryProperties properties,
                                                 LlmProperties llm,
                                                 VerboseLog verbose) {
        // 复盘模型：summary.model 优先，回落 llm.model
        String model = Texts.firstNonBlank(properties.model(), llm.model());
        NarratorAgent chat = llm.apiKey().isBlank() ? null
                : new NarratorAgent(AgentModels.openAi(
                        new LlmEndpoint(llm.baseUrl(), llm.apiKey(), model),
                        ChatOptions.narrative(properties.maxTokens()),
                        Duration.ofMillis(properties.timeoutMs()), false, verbose),
                DuelSummaryPrompts.SYSTEM, null);
        return new DuelSummaryService(lightRag, properties, chat, verbose);
    }
}
