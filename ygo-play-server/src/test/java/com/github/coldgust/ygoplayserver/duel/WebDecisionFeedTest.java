package com.github.coldgust.ygoplayserver.duel;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前端决策流的可见性（2026-11-14 用户指令）：① 唯一合法不上前端，但轨迹与日志照旧。
 */
class WebDecisionFeedTest {

    @Test
    void onlyChoiceIsHiddenFromTheWebStream() {
        assertFalse(WebDecisionFeed.visible(Map.of("kind", "EXEC_DECISION", "level", "ONLY_CHOICE")),
                "① 唯一合法不上前端");
        assertFalse(WebDecisionFeed.visible(Map.of("kind", "SUBMITTED", "level", "ONLY_CHOICE")),
                "提交事件同一口径");
        assertFalse(WebDecisionFeed.visible(Map.of("kind", "DECISION", "pathKind", "ONLY_CHOICE")),
                "轨迹原文用 pathKind 表达等级");
    }

    @Test
    void everythingElseStaysVisible() {
        assertTrue(WebDecisionFeed.visible(Map.of("kind", "EXEC_DECISION", "level", "LLM_CALL")));
        assertTrue(WebDecisionFeed.visible(Map.of("kind", "EXEC_DECISION", "level", "ZONE_HINT")));
        assertTrue(WebDecisionFeed.visible(Map.of("kind", "EXEC_DECISION", "level", "FALLBACK")));
        assertTrue(WebDecisionFeed.visible(Map.of("kind", "SUBMITTED", "submitted", "不响应")),
                "协议兜底（决策点构建失败）没有 level，仍要显示");
        assertTrue(WebDecisionFeed.visible(Map.of("kind", "DUEL_STATUS", "state", "RUNNING")));
    }
}
