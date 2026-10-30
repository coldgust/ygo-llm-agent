package com.github.coldgust.ygoprosdk.session;


/**
 * 建房参数（CTOS_CREATE_GAME 的 HostInfo，建房版 21 字节，字段顺序见规范 §5.2）。
 */
public record RoomOptions(
        int banlistHash,
        int rule,            // 0 OCG / 1 TCG / 2 双表
        int masterRule,      // 5 = MR5
        int mode,            // 0 单局 / 1 比赛 / 2 Tag
        boolean enablePriority,
        boolean noCheckDeck,
        boolean noShuffleDeck,
        int startLp,
        int startHand,
        int drawCount,
        int timeLimitSeconds,
        String roomName,
        String roomPassword
) {
    public static RoomOptions singleDuelDefaults() {
        return new RoomOptions(0, 0, 5, 0, false, false, false,
                8000, 5, 1, 180, "", "");
    }
}
