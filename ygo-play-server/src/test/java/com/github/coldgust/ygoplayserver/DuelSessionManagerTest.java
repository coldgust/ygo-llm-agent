package com.github.coldgust.ygoplayserver;

import com.github.coldgust.ygoplay.DuelRunResult;
import com.github.coldgust.ygoplay.DuelRunSpec;
import com.github.coldgust.ygoplay.RunStats;
import com.github.coldgust.ygoplay.DuelRunner;
import com.github.coldgust.ygoplayserver.config.PlayServerProperties;
import com.github.coldgust.ygoplayserver.duel.DuelExceptions;
import com.github.coldgust.ygoplayserver.duel.DuelExecutor;
import com.github.coldgust.ygoplayserver.duel.DuelRequest;
import com.github.coldgust.ygoplayserver.duel.DuelRunnerFactory;
import com.github.coldgust.ygoplayserver.duel.DuelService;
import com.github.coldgust.ygoplayserver.duel.DuelSessionManager;
import com.github.coldgust.ygoplayserver.duel.DuelStatus;
import com.github.coldgust.ygoplayserver.duel.DuelSummaryService;
import com.github.coldgust.ygoplayserver.duel.DuelView;
import com.github.coldgust.ygoplayserver.web.DuelEventHub;
import com.github.coldgust.ygoprosdk.session.DuelSession;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.springframework.mock.env.MockEnvironment;

class DuelSessionManagerTest {

    private static PlayServerProperties props() {
        return new PlayServerProperties("second", 1);
    }

    @Test
    void startsRunsAndStops() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        DuelSessionManager manager = new DuelSessionManager(props(), fakeFactory((spec, ref, stop) -> {
            started.countDown();
            while (!stop.getAsBoolean()) {
                Thread.sleep(10);
            }
            throw new DuelRunner.DuelStoppedException();
        }));

        DuelView view = manager.start(request());
        assertEquals(DuelStatus.STARTING.name(), view.status());
        assertTrue(started.await(2, TimeUnit.SECONDS));
        awaitStatus(manager, view.id(), DuelStatus.RUNNING);
        manager.stop(view.id());
        awaitStatus(manager, view.id(), DuelStatus.STOPPED);
    }

    @Test
    void finishesWithStats() throws Exception {
        DuelSessionManager manager = new DuelSessionManager(props(), fakeFactory((spec, ref, stop) ->
                new DuelRunResult(new RunStats(10, 0), null, spec.tracePath(), spec.duelLogPath())));

        DuelView view = manager.start(request());
        DuelView done = awaitStatus(manager, view.id(), DuelStatus.FINISHED);
        assertEquals(10, done.answered());
        assertEquals(0, done.fallbacks());
        assertNotNull(done.finishedAt());
    }

    @Test
    void rejectsSecondActiveDuel() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        DuelSessionManager manager = new DuelSessionManager(props(), fakeFactory((spec, ref, stop) -> {
            release.await(2, TimeUnit.SECONDS);
            return new DuelRunResult(new RunStats(0, 0), null, spec.tracePath(), spec.duelLogPath());
        }));

        manager.start(request());
        DuelExceptions.ConflictException e = assertThrows(DuelExceptions.ConflictException.class,
                () -> manager.start(request()));
        assertTrue(e.getMessage().contains("已有对局"));
        release.countDown();
    }

    @Test
    void validatesRequest() {
        DuelSessionManager manager = new DuelSessionManager(props(), fakeFactory((spec, ref, stop) -> null));

        DuelRequest both = new DuelRequest("127.0.0.1", 7911, "n", "second",
                "ydk", "/tmp/x.ydk", null, null, null, null, null, null);
        assertThrows(DuelExceptions.ValidationException.class, () -> manager.start(both));

        DuelRequest badPort = new DuelRequest("127.0.0.1", 70000, "n", "second",
                "ydk", null, null, null, null, null, null, null);
        assertThrows(DuelExceptions.ValidationException.class, () -> manager.start(badPort));
    }

    @Test
    void askPolicyWaitsForUserDecision() throws Exception {
        DuelSessionManager manager = new DuelSessionManager(props(), fakeFactory((spec, ref, stop) ->
                new DuelRunResult(new RunStats(3, 0), null, spec.tracePath(), spec.duelLogPath())),
                new DuelEventHub(),
                new DuelSummaryService(new MockEnvironment()));

        DuelView view = manager.start(request("ask"));
        DuelView done = awaitStatus(manager, view.id(), DuelStatus.FINISHED);
        assertEquals("PENDING", done.archiveState());

        // 决定写入 → 状态翻转（写库异步进行，LightRAG 不可达只记日志，不影响状态）
        DuelView accepted = manager.archiveDecision(view.id(), true);
        assertEquals("ACCEPTED", accepted.archiveState());

        DuelView again = manager.archiveDecision(view.id(), false);
        assertEquals("ACCEPTED", again.archiveState());
    }

    @Test
    void askPolicyDeclineKeepsPendingFromBeingStored() throws Exception {
        DuelSessionManager manager = new DuelSessionManager(props(), fakeFactory((spec, ref, stop) ->
                new DuelRunResult(new RunStats(1, 0), null, spec.tracePath(), spec.duelLogPath())),
                new DuelEventHub(),
                new DuelSummaryService(new MockEnvironment()));

        DuelView view = manager.start(request("ask"));
        awaitStatus(manager, view.id(), DuelStatus.FINISHED);
        DuelView declined = manager.archiveDecision(view.id(), false);
        assertEquals("DECLINED", declined.archiveState());
    }

    @Test
    void neverPolicyNeverMarksPending() throws Exception {
        DuelSessionManager manager = new DuelSessionManager(props(), fakeFactory((spec, ref, stop) ->
                new DuelRunResult(new RunStats(1, 0), null, spec.tracePath(), spec.duelLogPath())),
                new DuelEventHub(),
                new DuelSummaryService(new MockEnvironment()));

        DuelView view = manager.start(request("never"));
        DuelView done = awaitStatus(manager, view.id(), DuelStatus.FINISHED);
        assertEquals("never", done.archivePolicy());
        assertNull(done.archiveState());
        assertThrows(DuelExceptions.ConflictException.class, () -> manager.archiveDecision(view.id(), true));
    }

    @Test
    void validatesArchetype() {
        DuelSessionManager manager = new DuelSessionManager(props(), fakeFactory((spec, ref, stop) -> null));

        // 主题会拼进 combo 产物文件名：路径穿越必须被拒
        DuelRequest traversal = new DuelRequest("127.0.0.1", 7911, "n", "second",
                "main:1", null, "../evil", null, null, null, null, null);
        assertThrows(DuelExceptions.ValidationException.class, () -> manager.start(traversal));

        DuelRequest separator = new DuelRequest("127.0.0.1", 7911, "n", "second",
                "main:1", null, "a/b", null, null, null, null, null);
        assertThrows(DuelExceptions.ValidationException.class, () -> manager.start(separator));
    }

    @Test
    void acceptsNewArchetypeName() throws Exception {
        DuelSessionManager manager = new DuelSessionManager(props(), fakeFactory((spec, ref, stop) -> null));

        DuelRequest fresh = new DuelRequest("127.0.0.1", 7911, "n", "second",
                "main:1", null, "  白龙  ", null, null, null, null, null);
        DuelView view = manager.start(fresh);
        assertEquals("白龙", view.archetype());
        manager.stop(view.id());
    }

    private static DuelRequest request() {
        return request(null);
    }

    private static DuelRequest request(String archivePolicy) {
        return new DuelRequest("127.0.0.1", 7911, "ygo-server-test", "second",
                "main:1", null, null, null, null, null, null, archivePolicy);
    }

    private static DuelRunnerFactory fakeFactory(DuelExecutorStub stub) {
        return new DuelRunnerFactory(new DuelEventHub()) {
            @Override
            public DuelExecutor create(DuelRunSpec spec) {
                return stub::run;
            }
        };
    }

    private static DuelView awaitStatus(DuelService service, String id, DuelStatus expected)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(3));
        while (Instant.now().isBefore(deadline)) {
            DuelView view = service.get(id);
            if (view.status().equals(expected.name())) {
                return view;
            }
            Thread.sleep(10);
        }
        return service.get(id);
    }

    @FunctionalInterface
    private interface DuelExecutorStub {
        DuelRunResult run(DuelRunSpec spec, AtomicReference<DuelSession> ref,
                          BooleanSupplier stop) throws Exception;
    }
}
