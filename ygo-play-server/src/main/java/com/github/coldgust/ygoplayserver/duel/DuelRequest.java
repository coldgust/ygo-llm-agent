package com.github.coldgust.ygoplayserver.duel;

import java.util.Locale;

/**
 * 启动一局的 HTTP 请求（字段可选，缺省走服务配置）。
 *
 * @param deckYdk    .ydk 文本内容（与 deckPath 二选一）
 * @param deckPath   服务端本地 .ydk 路径（与 deckYdk 二选一）
 * @param deckStrategy 本卡组打法思路（用户手写，注入 LLM 上下文作为重点参考）
 * @param archivePolicy 对局库入库策略：{@code ask}（结束后询问用户）/
 *                      {@code auto}（LLM 写复盘并判定是否有入库价值）/
 *                      {@code always}（全部入库）/ {@code never}（全部不入库）。
 *                      缺省按"不入库"处理——对局库不再默认收录。
 */
public record DuelRequest(
        String host,
        Integer port,
        String name,
        String move,
        String deckYdk,
        String deckPath,
        String archetype,
        Boolean comboRecord,
        String duelLogPath,
        String tracePath,
        String deckStrategy,
        String archivePolicy) {

    /** 归一化入库策略；空值/未知值按 never（不写库）处理。 */
    public String archivePolicyOrDefault() {
        if (archivePolicy == null) {
            return "never";
        }
        return switch (archivePolicy.trim().toLowerCase(Locale.ROOT)) {
            case "ask", "auto", "always", "never" -> archivePolicy.trim().toLowerCase(Locale.ROOT);
            default -> "never";
        };
    }
}
