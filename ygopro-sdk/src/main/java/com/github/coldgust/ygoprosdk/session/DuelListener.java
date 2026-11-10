package com.github.coldgust.ygoprosdk.session;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.decision.ChoiceSelection;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.DuelResult;

/**
 * 会话事件监听（异步观察；不参与决策——决策走 {@link DuelSession#awaitChoice}）。
 */
public interface DuelListener {

    /** 战场镜像更新（每条状态事件后回调，值为不可变快照）。 */
    default void onStateUpdate(DuelState state) {
    }

    /** 出现了待决策的请求（与 awaitChoice 返回的是同一个对象）。 */
    default void onChoiceAvailable(ChoiceRequest request) {
    }

    /** 应答已发出（PLAYER=决策者提交；FALLBACK/SDK 自动兜底亦会回调）。 */
    default void onResponseSent(ChoiceRequest request, ChoiceSelection selection,
                                ResponseSource source, int retryCount) {
    }

    /** 服务端打回上一次应答（MSG_RETRY）。 */
    default void onRetryIssued(ChoiceRequest request, int retryCount) {
    }

    /**
     * 效果发动入链（MSG_CHAINING）。
     *
     * <p>状态 diff 看不到"怪兽发动了效果"（卡不动），combo 录入等场景靠它补齐
     * "用户发动了哪张卡的哪个效果"。controllerSeat 为发动方的绝对座位号。
     */
    default void onEffectActivated(int controllerSeat, long passcode, long description) {
    }

    default void onDuelEnd(DuelResult result) {
    }

    /** 协议层错误（如 DECKERROR、解码告警）。会话继续运行。 */
    default void onError(Throwable error) {
    }

    default void onDisconnected() {
    }
}
