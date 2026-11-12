package com.github.coldgust.ygoprosdk.session;

import com.github.coldgust.ygodomain.duel.DuelState;
import com.github.coldgust.ygodomain.duel.Phase;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.duel.PlayerState;
import com.github.coldgust.ygoduellog.DuelRecorder;
import com.github.coldgust.ygoduellog.event.DuelEvent;
import com.github.coldgust.ygoduellog.event.DuelEventKind;
import com.github.coldgust.ygoduellog.event.DuelEventPayload;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：回合轮换帧上的 phase 是投影缺省/上回合残留（NEW_TURN 不重置 phaseBits），
 * 不该作为 PHASE_ENTERED 宣告——开局会多出一条"进入主要阶段1"排在抽卡阶段之前。
 */
class DuelLogBridgeTest {

    /** 只记录事件种类的假记录器。 */
    private static final class FakeRecorder implements DuelRecorder {
        final List<DuelEventKind> kinds = new ArrayList<>();

        @Override
        public long append(String actor, DuelEventKind kind, DuelEventPayload payload) {
            kinds.add(kind);
            return kinds.size();
        }

        @Override
        public void updateContext(int turn, String phase, long turnStartWallMillis) {
        }

        @Override
        public void subscribe(java.util.function.Consumer<DuelEvent> listener) {
        }

        @Override
        public void attachSink(com.github.coldgust.ygoduellog.DuelEventSink sink) {
        }

        @Override
        public void close() {
        }
    }

    private static DuelState state(int turn, PlayerSide turnPlayer, Phase phase) {
        PlayerState self = new PlayerState(PlayerSide.SELF, 0, "me", 8000,
                List.of(), 40, 15, List.of(), List.of(), List.of());
        PlayerState opponent = new PlayerState(PlayerSide.OPPONENT, 1, "opp", 8000,
                List.of(), 40, 15, List.of(), List.of(), List.of());
        return new DuelState(5, turn, turnPlayer, phase,
                Map.of(PlayerSide.SELF, self, PlayerSide.OPPONENT, opponent),
                Map.of(), List.of(), null, 0);
    }

    private static long count(List<DuelEventKind> kinds, DuelEventKind kind) {
        return kinds.stream().filter(k -> k == kind).count();
    }

    @Test
    void turnRotationSnapshotDoesNotAnnouncePhase() {
        FakeRecorder recorder = new FakeRecorder();
        DuelLogBridge bridge = new DuelLogBridge(recorder, "self", false);

        // 首帧（MSG_START 后）：turn=1、phase 为投影缺省 MAIN_PHASE_1
        bridge.onStateUpdate(state(1, PlayerSide.SELF, Phase.MAIN_PHASE_1));
        assertEquals(0, count(recorder.kinds, DuelEventKind.PHASE_ENTERED),
                "回合轮换帧不得宣告 phase（缺省值会冒充主要阶段1）:\n" + recorder.kinds);
        assertEquals(1, count(recorder.kinds, DuelEventKind.TURN_STARTED));

        // 引擎宣告真实阶段（MSG_NEW_PHASE）→ 照发
        bridge.onStateUpdate(state(1, PlayerSide.SELF, Phase.DRAW_PHASE));
        assertEquals(1, count(recorder.kinds, DuelEventKind.PHASE_ENTERED), "抽卡阶段宣告一次");

        // 第二回合轮换帧携带上回合残留的 phase（END_PHASE）→ 不宣告
        bridge.onStateUpdate(state(2, PlayerSide.OPPONENT, Phase.END_PHASE));
        assertEquals(1, count(recorder.kinds, DuelEventKind.PHASE_ENTERED),
                "轮换帧残留 phase 不得宣告:\n" + recorder.kinds);
        bridge.onStateUpdate(state(2, PlayerSide.OPPONENT, Phase.MAIN_PHASE_1));
        assertEquals(2, count(recorder.kinds, DuelEventKind.PHASE_ENTERED));
        assertTrue(recorder.kinds.indexOf(DuelEventKind.TURN_STARTED)
                        < recorder.kinds.lastIndexOf(DuelEventKind.PHASE_ENTERED),
                "回合开始先于阶段宣告");
    }
}
