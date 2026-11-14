package com.github.coldgust.ygoprosdk.session;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;
import com.github.coldgust.ygodomain.decision.ChoiceSelection;
import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.DuelResult;
import com.github.coldgust.ygodomain.zone.ZoneRef;

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
    default void onEffectActivated(int controllerSeat, long passcode, long description,
                                   Integer chainIndex, ZoneRef sourceZone) {
    }

    /**
     * 连锁第 chainIndex 环开始处理（MSG_CHAIN_SOLVING）。
     *
     * <p>记录里这一行就是"谁的效果处理了"的分界：它之后的卡移动/效果结果都属于这一环。
     */
    default void onChainSolving(int chainIndex, long passcode) {
    }

    /**
     * 连锁第 chainIndex 环被无效（MSG_CHAIN_NEGATED / MSG_CHAIN_DISABLED）。
     *
     * <p>"效果无效"是记录里的关键事实：模型要知道对方那个效果没结算。
     * passcode 从镜像的链上取（0 = 未知）。
     */
    default void onChainNegated(int chainIndex, long passcode) {
    }

    /**
     * 一张卡被公开（MSG_CONFIRM_CARDS / MSG_CARD_SELECTED）。
     *
     * <p>检索的卡号只能从这里拿到：进手牌的 MSG_MOVE 被服务端抹零，亮牌消息才是公开信息。
     */
    default void onCardRevealed(int controllerSeat, long passcode) {
    }

    /** 整条连锁处理完毕（MSG_CHAIN_END）。 */
    default void onChainEnd(int chainLength) {
    }

    default void onDuelEnd(DuelResult result) {
    }

    /** 协议层错误（如 DECKERROR、解码告警）。会话继续运行。 */
    default void onError(Throwable error) {
    }

    default void onDisconnected() {
    }
}
