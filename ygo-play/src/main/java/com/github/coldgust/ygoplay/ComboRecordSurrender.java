package com.github.coldgust.ygoplay;

import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygoprosdk.session.DuelListener;
import com.github.coldgust.ygoprosdk.session.DuelSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

final class ComboRecordSurrender implements DuelListener {

    private static final Logger log = LoggerFactory.getLogger(ComboRecordSurrender.class);

    interface SurrenderAction {
        void run() throws Exception;
    }

    private final SurrenderAction surrender;
    private final AtomicBoolean fired = new AtomicBoolean();
    private volatile boolean surrendered;

    ComboRecordSurrender(SurrenderAction surrender) {
        this.surrender = surrender;
    }

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
