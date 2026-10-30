package com.github.coldgust.ygoplayserver.duel;

import com.github.coldgust.ygoprosdk.session.DuelSession;
import com.github.coldgust.ygoplay.DuelRunResult;
import com.github.coldgust.ygoplay.DuelRunSpec;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** 执行一局（可替换为测试桩）。 */
public interface DuelExecutor {

    DuelRunResult run(DuelRunSpec spec, AtomicReference<DuelSession> sessionRef,
                      BooleanSupplier stopRequested) throws Exception;
}
