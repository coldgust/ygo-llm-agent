package com.github.coldgust.ygoplayserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 连招库录入（PG combo_record，唯一 combo 存储）。
 *
 * @param summaryModel 录入摘要模型；留空 = 用 ygo.llm.model
 */
@ConfigurationProperties(prefix = "ygo.combo")
public record ComboProperties(String summaryModel) {

    public ComboProperties {
        if (summaryModel != null && summaryModel.isBlank()) summaryModel = null;
    }
}
