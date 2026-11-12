package com.github.coldgust.ygoplayserver.config;

import com.github.coldgust.ygoagent.support.Texts;
import com.github.coldgust.ygoagent.llm.ChatClient;
import com.github.coldgust.ygoagent.llm.LlmEndpoint;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoplayserver.duel.DuelSummaryService;
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
        ChatClient chat = llm.apiKey().isBlank() ? null
                : ChatClient.openAiCompatible(new LlmEndpoint(llm.baseUrl(), llm.apiKey(), model), verbose);
        return new DuelSummaryService(lightRag, properties, chat, verbose);
    }
}
