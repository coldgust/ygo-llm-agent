package com.github.coldgust.ygoplay;

import java.nio.file.Path;

/**
 * 一次 combo 实战录入的素材：对局结束后，从对局日志里提取用户回合的操作序列。
 *
 * @param duelId      对局 id（duel-log 文件名去扩展名，与 SSE 频道一致）
 * @param archetype   卡组主题（必填：录入归属与后续上下文注入的键；启动请求侧已强制校验）
 * @param duelLogPath 对局日志 JSONL（用户回合的事件全在里面）
 */
public record ComboRecording(String duelId, String archetype, Path duelLogPath) {
}
