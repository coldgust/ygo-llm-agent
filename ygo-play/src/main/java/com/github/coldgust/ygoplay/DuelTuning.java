package com.github.coldgust.ygoplay;

/**
 * 一局对局的运行调参。由组装根解析（CLI 从 env+.env；server 从 Spring 配置）后放进 {@link DuelRunSpec}。
 *
 * @param duelLogRawFrames    原始协议帧写入对局记录（审计解码问题用，体积大；默认关）
 * @param choiceTimeoutMillis 看门狗：决策点因解码/映射异常没拿到应答时，超时由 SDK 提交保底应答的等待
 *                            （不设就是"永久卡住"，实机出现过位置请求 0 候选 → 应答非法 → 对局冻结；默认 25s，下限 0）
 * @param startWaitMillis     等待对手进房时长（实机调试常"先起客户端、再手动开对手"，需放宽；默认 180s，下限 10s）
 * @param comboContextLimit   己方主题注入规划器上下文的录入条数上限（1–30，默认 10）
 */
public record DuelTuning(
        boolean duelLogRawFrames,
        long choiceTimeoutMillis,
        long startWaitMillis,
        int comboContextLimit) {

    public DuelTuning {
        choiceTimeoutMillis = Math.max(0L, choiceTimeoutMillis);
        startWaitMillis = Math.max(10_000L, startWaitMillis);
        comboContextLimit = Math.max(1, Math.min(30, comboContextLimit));
    }

    public static DuelTuning defaults() {
        return new DuelTuning(false, 25_000L, 180_000L, 10);
    }
}
