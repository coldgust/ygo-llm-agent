package com.github.coldgust.ygoplayserver.duel;

import com.github.coldgust.ygoprosdk.session.DuelSession;
import com.github.coldgust.ygoplay.DuelRunResult;
import com.github.coldgust.ygoplay.DuelRunSpec;
import com.github.coldgust.ygoplay.DuelRunner;
import com.github.coldgust.ygoplay.DuelTuning;
import com.github.coldgust.ygoplayserver.config.PlayServerProperties;
import com.github.coldgust.ygoplayserver.web.DuelEventHub;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.LinkedHashMap;

@Service
public class DuelSessionManager implements DuelService {

    private static final Logger log = LoggerFactory.getLogger(DuelSessionManager.class);

    private final PlayServerProperties properties;
    private final DuelRunnerFactory runnerFactory;
    private final DuelEventHub eventHub;
    private final DuelSummaryService summaryService;
    private final Map<String, DuelJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Object startLock = new Object();

    public DuelSessionManager(PlayServerProperties properties, DuelRunnerFactory runnerFactory) {
        this(properties, runnerFactory, new DuelEventHub(), null);
    }

    @Autowired
    public DuelSessionManager(PlayServerProperties properties, DuelRunnerFactory runnerFactory,
                              DuelEventHub eventHub, DuelSummaryService summaryService) {
        this.properties = properties;
        this.runnerFactory = runnerFactory;
        this.eventHub = eventHub;
        this.summaryService = summaryService;
    }

    @Override
    public DuelView start(DuelRequest request) {
        String id = UUID.randomUUID().toString();
        DuelRunSpec spec = toSpec(id, request);
        synchronized (startLock) {
            long active = jobs.values().stream().filter(job -> !job.status.terminal()).count();
            if (active >= properties.maxConcurrentDuels()) {
                throw new DuelExceptions.ConflictException("已有对局进行中（最多 "
                        + properties.maxConcurrentDuels() + " 局）");
            }
            DuelJob job = new DuelJob(id, request, spec);
            jobs.put(id, job);
            job.future = executor.submit(() -> execute(job));
            return toView(job);
        }
    }

    @Override
    public DuelView get(String id) {
        return toView(require(id));
    }

    @Override
    public List<DuelView> list() {
        return jobs.values().stream()
                .sorted(Comparator.comparing((DuelJob job) -> job.startedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toView)
                .toList();
    }

    @Override
    public DuelView stop(String id) {
        DuelJob job = require(id);
        if (job.status.terminal()) {
            return toView(job);
        }
        job.stopRequested.set(true);
        job.status = DuelStatus.STOPPING;
        DuelSession session = job.sessionRef.get();
        if (session != null) {
            try {
                session.close();
            } catch (RuntimeException e) {
                log.warn("关闭对局连接失败: {}", e.toString());
            }
        }
        Future<?> future = job.future;
        if (future != null && future.cancel(true) && job.status == DuelStatus.STOPPING
                && job.result == null) {
            job.status = DuelStatus.STOPPED;
            job.finishedAt = Instant.now();
        }
        return toView(job);
    }

    @PreDestroy
    void shutdown() {
        jobs.values().forEach(job -> {
            job.stopRequested.set(true);
            DuelSession session = job.sessionRef.get();
            if (session != null) {
                try {
                    session.close();
                } catch (RuntimeException ignored) {
                }
            }
        });
        executor.shutdownNow();
    }

    private void execute(DuelJob job) {
        job.status = DuelStatus.RUNNING;
        job.startedAt = Instant.now();
        publishStatus(job, "RUNNING");
        try {
            DuelExecutor runner = runnerFactory.create(job.spec);
            DuelRunResult result = runner.run(job.spec, job.sessionRef, job.stopRequested::get);
            job.result = result;
            job.status = DuelStatus.FINISHED;
        } catch (DuelRunner.DuelStoppedException e) {
            job.status = DuelStatus.STOPPED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            job.status = DuelStatus.STOPPED;
        } catch (Throwable t) {
            // 必须接住 Throwable：Error 落到这里之前只会把任务线程悄悄带走——
            // Future 没人 get，异常无人知晓，job 永远停在 RUNNING，
            // 既不发终态事件（前端卡死）又占住 max-concurrent-duels 名额（开不了新局）。
            job.status = DuelStatus.FAILED;
            job.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            log.error("对局 {} 异常终止（{}）", job.id, t.getClass().getSimpleName(), t);
        } finally {
            job.finishedAt = Instant.now();
            // 入库策略分流（只在正常结束时；stopped/failed 的素材不进对局库）。
            // ask 模式在这里挂起等用户决定，archiveState 必须在终态事件之前就位，前端才问得出来。
            String policy = job.request.archivePolicyOrDefault();
            if (job.status == DuelStatus.FINISHED && summaryService != null && summaryService.enabled()) {
                switch (policy) {
                    case "always" -> summaryService.ingestAsync(job.id, job.spec, job.result);
                    case "auto" -> summaryService.ingestJudgedAsync(job.id, job.spec, job.result);
                    case "ask" -> job.archiveState = "PENDING";
                    default -> { }
                }
            }
            publishFinal(job);
        }
    }

    @Override
    public DuelView archiveDecision(String id, boolean store) {
        DuelJob job = require(id);
        if (!"ask".equals(job.request.archivePolicyOrDefault())) {
            throw new DuelExceptions.ConflictException("本局不是「结束后询问」模式，无需决定入库");
        }
        if (job.status != DuelStatus.FINISHED) {
            throw new DuelExceptions.ConflictException("对局尚未正常结束，不能决定入库");
        }
        synchronized (job) {
            if (!"PENDING".equals(job.archiveState)) {
                return toView(job);
            }
            job.archiveState = store ? "ACCEPTED" : "DECLINED";
        }
        if (store && summaryService != null) {
            summaryService.ingestAsync(job.id, job.spec, job.result);
        }
        log.info("对局 {} 入库决定：{}", id, store ? "写入对局库" : "跳过");
        return toView(job);
    }

    private void publishFinal(DuelJob job) {
        try {
            var result = job.result;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("duelId", job.id);
            payload.put("status", job.status.name());
            payload.put("answered", result == null ? null : result.stats().answered());
            payload.put("fallbacks", result == null ? null : result.stats().fallbacks());
            payload.put("winner", result == null || result.duelResult() == null ? null
                    : String.valueOf(result.duelResult().winner()));
            payload.put("reason", result == null || result.duelResult() == null ? null
                    : String.valueOf(result.duelResult().reason()));
            payload.put("tracePath", job.spec.tracePath().toString());
            payload.put("duelLogPath", job.spec.duelLogPath().toString());
            payload.put("archetype", job.spec.archetype());
            payload.put("archivePolicy", job.request.archivePolicyOrDefault());
            payload.put("archiveState", job.archiveState);
            eventHub.complete(traceId(job), "DUEL_STATUS", payload);
        } catch (RuntimeException ignored) {
        }
    }

    private void publishStatus(DuelJob job, String status) {
        try {
            eventHub.publish(traceId(job), "DUEL_STATUS", Map.of(
                    "duelId", job.id, "status", status));
        } catch (RuntimeException ignored) {
        }
    }

    private DuelRunSpec toSpec(String id, DuelRequest request) {
        String host = blank(request.host()) ? properties.host() : request.host();
        int port = request.port() == null ? properties.port() : request.port();
        if (port < 1 || port > 65_535) {
            throw new DuelExceptions.ValidationException("port 非法: " + port);
        }
        String name = blank(request.name()) ? properties.playerName() : request.name();
        String move = blank(request.move()) ? properties.defaultMove() : request.move();
        if (!"first".equalsIgnoreCase(move) && !"second".equalsIgnoreCase(move)) {
            throw new DuelExceptions.ValidationException("move 只能是 first/second: " + move);
        }
        boolean hasYdk = !blank(request.deckYdk());
        boolean hasPath = !blank(request.deckPath());
        if (hasYdk == hasPath) {
            throw new DuelExceptions.ValidationException("deckYdk 与 deckPath 必须且只能提供一个");
        }
        Path deckPath = hasPath ? Path.of(request.deckPath().replaceFirst("^~", System.getProperty("user.home"))) : null;
        if (deckPath != null && !Files.isRegularFile(deckPath)) {
            throw new DuelExceptions.ValidationException("deckPath 不存在: " + deckPath);
        }
        // 主题允许"建新的"（combo 录入以它命名产物文件），所以必须挡住路径穿越与文件名非法字符
        String archetype = normalizeArchetype(request.archetype());
        Path duelLog = blank(request.duelLogPath())
                ? Path.of(properties.duelLogDir(), "server-" + id + ".jsonl")
                : Path.of(request.duelLogPath());
        Path trace = blank(request.tracePath())
                ? Path.of(properties.traceDir(), "server-" + id + ".jsonl")
                : Path.of(request.tracePath());
        boolean comboRecord = Boolean.TRUE.equals(request.comboRecord());
        if (comboRecord && blank(archetype)) {
            throw new DuelExceptions.ValidationException(
                    "combo 录入必须指定卡组主题（选已有或输入新名字）——它是录入归档与后续对局上下文注入的键");
        }
        return new DuelRunSpec(host, port, name, move, request.deckYdk(), deckPath,
                archetype, comboRecord, duelLog, trace,
                blank(request.deckStrategy()) ? null : request.deckStrategy().trim(),
                new DuelTuning(properties.duelLogRawFrames(), properties.choiceTimeoutMs(),
                        properties.startWaitMs(), properties.comboContextLimit()));
    }

    private static String normalizeArchetype(String raw) {
        if (blank(raw)) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.equals(".") || value.equals("..")
                || value.length() > 40
                || value.chars().anyMatch(c -> c == '/' || c == '\\' || c == 0 || Character.isISOControl(c))) {
            throw new DuelExceptions.ValidationException(
                    "卡组主题非法（不能含 / \\ 等路径字符，长度 ≤ 40）: " + value);
        }
        return value;
    }

    private DuelJob require(String id) {
        DuelJob job = jobs.get(id);
        if (job == null) {
            throw new DuelExceptions.NotFoundException("对局不存在: " + id);
        }
        return job;
    }

    private DuelView toView(DuelJob job) {
        DuelRunResult result = job.result;
        Integer answered = result == null ? null : result.stats().answered();
        Integer fallbacks = result == null ? null : result.stats().fallbacks();
        String winner = result == null || result.duelResult() == null ? null
                : String.valueOf(result.duelResult().winner());
        String reason = result == null || result.duelResult() == null ? null
                : String.valueOf(result.duelResult().reason());
        return new DuelView(
                job.id, job.status.name(), job.spec.host(), job.spec.port(), job.spec.name(),
                job.spec.move(), (job.spec.deckYdkText() != null ? "inline" : String.valueOf(job.spec.deckPath())),
                job.spec.archetype(),
                job.startedAt, job.finishedAt, answered, fallbacks,
                winner, reason, job.error,
                String.valueOf(job.spec.tracePath()), String.valueOf(job.spec.duelLogPath()),
                job.request.archivePolicyOrDefault(), job.archiveState);
    }

    @Override
    public String traceId(String id) {
        return traceId(require(id));
    }

    private static String traceId(DuelJob job) {
        String name = job.spec.tracePath().getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static final class DuelJob {
        private final String id;
        private final DuelRequest request;
        private final DuelRunSpec spec;
        private final AtomicReference<DuelSession> sessionRef = new AtomicReference<>();
        private final AtomicBoolean stopRequested = new AtomicBoolean();
        private volatile DuelStatus status = DuelStatus.STARTING;
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile DuelRunResult result;
        private volatile String error;
        private volatile String archiveState;
        private volatile Future<?> future;

        private DuelJob(String id, DuelRequest request, DuelRunSpec spec) {
            this.id = id;
            this.request = request;
            this.spec = spec;
        }
    }
}
