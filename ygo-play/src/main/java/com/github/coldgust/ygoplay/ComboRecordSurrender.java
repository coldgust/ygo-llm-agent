package com.github.coldgust.ygoplay;

import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygoprosdk.session.DuelListener;
import com.github.coldgust.ygoprosdk.session.DuelSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * combo 录入守卫：用户先手打完、轮到我方（AI）回合的瞬间立即认输。
 *
 * <p>录入只关心用户实际打出的操作；AI 不思考、不行动，认输既是对局终点，
 * 也是"录制结束"的信号。后手保证 {@code turnPlayer == SELF} 首次成立时
 * 就是 turn ≥ 2（用户的回合已结束），不会误伤开局。
 */
final class ComboRecordSurrender implements DuelListener {

    private static final Logger log = LoggerFactory.getLogger(ComboRecordSurrender.class);

    /** 认输动作（{@link DuelSession#surrender} 声明抛检查异常）。 */
    interface SurrenderAction {
        void run() throws Exception;
    }

    private final SurrenderAction surrender;
    private final AtomicBoolean fired = new AtomicBoolean();
    private volatile boolean surrendered;

    ComboRecordSurrender(SurrenderAction surrender) {
        this.surrender = surrender;
    }

    /** 是否已触发认输（true = 用户回合有内容，值得走落库流程）。 */
    boolean surrendered() {
        return surrendered;
    }

    @Override
    public void onStateUpdate(DuelState state) {
        if (state == null || state.isFinished() || state.turnNumber() < 2) {
            return;
        }
        if (state.turnPlayer() != PlayerSide.SELF) {
            return;
        }
        if (!fired.compareAndSet(false, true)) {
            return;
        }
        surrendered = true;
        log.info("combo 录入：用户回合结束（{} 回合），自动认输结束录制", state.turnNumber());
        try {
            surrender.run();
        } catch (Exception e) {
            log.warn("combo 录入认输失败（对局照常进行）: {}", e.toString());
        }
    }
}
