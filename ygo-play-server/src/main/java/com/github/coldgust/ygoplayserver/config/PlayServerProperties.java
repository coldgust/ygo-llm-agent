package com.github.coldgust.ygoplayserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对局运行配置（ygo.play）。
 *
 * @param defaultMove        默认先后手（second）
 * @param maxConcurrentDuels 并发对局上限
 * @param duelLogRawFrames   原始协议帧写入对局记录（审计解码问题用，体积大；默认关）
 * @param choiceTimeoutMs    看门狗：决策点未拿到应答时由 SDK 提交保底应答的等待 ms（默认 25000）
 * @param startWaitMs        等待对手进房时长 ms（实机调试需放宽；默认 180000，下限 10000）
 * @param comboContextLimit  己方主题注入规划器上下文的录入条数上限（1–30，默认 10）
 */
@ConfigurationProperties(prefix = "ygo.play")
public record PlayServerProperties(
        String defaultMove,
        int maxConcurrentDuels,
        Boolean duelLogRawFrames,
        Long choiceTimeoutMs,
        Long startWaitMs,
        Integer comboContextLimit) {

    public PlayServerProperties {
        if (defaultMove == null || defaultMove.isBlank()) defaultMove = "second";
        if (maxConcurrentDuels <= 0) maxConcurrentDuels = 1;
        if (duelLogRawFrames == null) duelLogRawFrames = false;
        if (choiceTimeoutMs == null || choiceTimeoutMs < 0) choiceTimeoutMs = 25_000L;
        if (startWaitMs == null || startWaitMs < 10_000L) startWaitMs = 180_000L;
        if (comboContextLimit == null) comboContextLimit = 10;
        comboContextLimit = Math.max(1, Math.min(30, comboContextLimit));
    }
}
