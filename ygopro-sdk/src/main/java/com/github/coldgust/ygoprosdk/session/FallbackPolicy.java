package com.github.coldgust.ygoprosdk.session;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.decision.ChoiceSelection;

/**
 * 保底策略（规范 §10.4 / §10.6）：决策超时或 MSG_RETRY 重答时由 SDK 自动提交的保守选择。
 * Agent 可注入自定义实现覆盖。
 */
public interface FallbackPolicy {

    /**
     * 为请求生成保守应答。
     *
     * @param request    待回答的请求
     * @param retryCount 本请求已被 MSG_RETRY 打回的次数（0 = 首次）
     */
    ChoiceSelection fallback(ChoiceRequest request, int retryCount);
}
