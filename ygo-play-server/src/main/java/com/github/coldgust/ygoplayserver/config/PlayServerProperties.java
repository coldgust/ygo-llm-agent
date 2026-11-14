package com.github.coldgust.ygoplayserver.config;

import com.github.coldgust.ygoplay.DuelTuning;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 对局运行配置（ygo.play）。键来自 .env / 环境变量，经 application.yaml 的 {@code ${ENV:...}} 桥接。
 * 默认值分级：环境相关项（host/端口/玩家名/归档目录）默认在 application.yaml，Java 必填校验缺失即抛点名；
 * 运行调参（原始帧/看门狗/进房等待/combo 上限）机器无关，代码默认在本构造器（与 DuelTuning.defaults 同值）；
 * 决策器预算表默认在 RunnerConfig.defaults（runner* 字段 null = 继承；覆盖时在 yaml 加 ${RUNNER_*} 桥接）。
 * 运行调参的代码默认直接引用 DuelTuning.defaults（单源）。
 *
 * @param host                YGOPro 服务地址（YGO_HOST；DuelRequest.host 未提供时的回落）
 * @param port                YGOPro 服务端口（YGO_PORT；DuelRequest.port 未提供时的回落）
 * @param playerName          房间内玩家名（YGO_PLAYER_NAME；DuelRequest.name 未提供时的回落）
 * @param defaultMove         默认先后手（first/second；DuelRequest.move 未提供时的回落）
 * @param maxConcurrentDuels  并发对局上限（下限 1）
 * @param duelLogDir          对局记录 JSONL 目录（DUEL_LOG_DIR；文件名自动带对局 id）
 * @param traceDir            决策轨迹 JSONL 目录（TRACE_DIR）
 * @param duelLogRawFrames    原始协议帧写入对局记录（审计解码问题用，体积大；默认关）
 * @param choiceTimeoutMs     看门狗：决策点未拿到应答时由 SDK 提交保底应答的等待 ms（下限 0）
 * @param startWaitMs         等待对手进房时长 ms（下限 10000）
 * @param comboContextLimit   己方主题注入规划器上下文的录入条数上限（1–30）
 * @param runnerPollMs        决策器会话轮询间隔 ms（RUNNER_POLL_MS）
 * @param runnerDefaultBudgetMs 决策器未单列预算请求类型的兜底预算 ms（RUNNER_DEFAULT_BUDGET_MS）
 * @param runnerBudgets       逐请求类型决策预算 ms（键 = RequestKind 名，与 CLI 的 RUNNER_BUDGET_*_MS 同源）
 */
@ConfigurationProperties(prefix = "ygo.play")
public record PlayServerProperties(
        String host,
        Integer port,
        String playerName,
        String defaultMove,
        Integer maxConcurrentDuels,
        String duelLogDir,
        String traceDir,
        Boolean duelLogRawFrames,
        Long choiceTimeoutMs,
        Long startWaitMs,
        Integer comboContextLimit,
        Integer runnerPollMs,
        Integer runnerDefaultBudgetMs,
        Map<String, Long> runnerBudgets) {

    public PlayServerProperties {
        if (host == null || host.isBlank()) {
            throw missing("ygo.play.host (YGO_HOST)");
        }
        if (port == null) {
            throw missing("ygo.play.port (YGO_PORT)");
        }
        if (playerName == null || playerName.isBlank()) {
            throw missing("ygo.play.player-name (YGO_PLAYER_NAME)");
        }
        if (defaultMove == null || defaultMove.isBlank()) {
            throw missing("ygo.play.default-move");
        }
        maxConcurrentDuels = Math.max(1, maxConcurrentDuels == null ? 0 : maxConcurrentDuels);
        if (duelLogDir == null || duelLogDir.isBlank()) {
            throw missing("ygo.play.duel-log-dir (DUEL_LOG_DIR)");
        }
        if (traceDir == null || traceDir.isBlank()) {
            throw missing("ygo.play.trace-dir (TRACE_DIR)");
        }
        // 运行调参：机器无关，代码默认直接引用 DuelTuning.defaults（单源，避免两处字面量漂移），配置仅做覆盖
        DuelTuning tuningDefaults = DuelTuning.defaults();
        duelLogRawFrames = Boolean.TRUE.equals(duelLogRawFrames);
        choiceTimeoutMs = choiceTimeoutMs == null
                ? tuningDefaults.choiceTimeoutMillis() : Math.max(0L, choiceTimeoutMs);
        startWaitMs = startWaitMs == null
                ? tuningDefaults.startWaitMillis() : Math.max(10_000L, startWaitMs);
        comboContextLimit = comboContextLimit == null
                ? tuningDefaults.comboContextLimit() : Math.max(1, Math.min(30, comboContextLimit));
    }

    private static IllegalStateException missing(String key) {
        return new IllegalStateException("缺少必填配置 " + key
                + "（application.yaml 的 ${ENV:默认} 桥接缺失或值为空）");
    }
}
