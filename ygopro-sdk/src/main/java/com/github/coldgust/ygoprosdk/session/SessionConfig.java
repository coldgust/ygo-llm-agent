package com.github.coldgust.ygoprosdk.session;

import com.github.coldgust.ygodomain.deck.Deck;
import com.github.coldgust.ygoprosdk.spi.CardInfoResolver;

import java.time.Duration;
import java.nio.file.Path;

/**
 * 会话配置。
 *
 * @param host              服务端地址
 * @param port              服务端端口（默认 7911）
 * @param playerName        玩家名（定长 40 字节 UTF-16LE，超长截断）
 * @param deck              卡组（就绪前上传）
 * @param firstPlayer       获得先后攻选择权时要先攻还是后攻
 * @param joinVersion       CTOS_JOIN_GAME 的 version 字段（服务端不校验，0x1353 最通用）
 * @param joinPassword      房间密码
 * @param createRoom        是否由本端建房（true 时使用 roomOptions）
 * @param roomOptions       建房参数（createRoom = true 时必填）
 * @param cardResolver      卡牌定义解析（可空：只携带 passcode）
 * @param fallbackPolicy    保底策略（空则用内置默认）
 * @param choiceTimeout     决策超时：超时后 SDK 自动提交保底应答（空 = 不超时，依赖房间计时）
 * @param answerDelayMillis 应答前的最小延迟（默认 150ms，模拟人类节奏并规避服务端竞态）
 * @param retryLimit        MSG_RETRY 连续重答上限，超过即投降
 * @param duelLogPath       对局记录 JSONL 落盘路径（null = 仅内存）
 * @param duelLogRawFrames  是否把原始协议帧写入对局记录（审计用，体积大）
 */
public record SessionConfig(
        String host,
        int port,
        String playerName,
        Deck deck,
        FirstMove firstPlayer,
        int joinVersion,
        String joinPassword,
        boolean createRoom,
        RoomOptions roomOptions,
        CardInfoResolver cardResolver,
        FallbackPolicy fallbackPolicy,
        Duration choiceTimeout,
        int answerDelayMillis,
        int retryLimit,
        Path duelLogPath,
        boolean duelLogRawFrames,
        boolean duelLogDecisionContext
) {

    /** 获得先后攻选择权时的取向。 */
    public enum FirstMove {
        /** 请求先攻（CTOS_TP_RESULT = 1）。 */
        FIRST,
        /** 选择后攻（CTOS_TP_RESULT = 0）。 */
        SECOND
    }

    public static Builder builder(String host, String playerName) {
        return new Builder(host, playerName);
    }

    public static final class Builder {
        private final String host;
        private final String playerName;
        private int port = 7911;
        private Deck deck = Deck.empty();
        private FirstMove firstPlayer = FirstMove.SECOND;
        private int joinVersion = 0x1353;
        private String joinPassword = "";
        private boolean createRoom;
        private RoomOptions roomOptions;
        private CardInfoResolver cardResolver;
        private FallbackPolicy fallbackPolicy;
        private Duration choiceTimeout;
        private int answerDelayMillis = 150;
        private int retryLimit = 12;
        private Path duelLogPath;
        private boolean duelLogRawFrames;
        private boolean duelLogDecisionContext;

        private Builder(String host, String playerName) {
            this.host = host;
            this.playerName = playerName;
        }

        public Builder port(int port) { this.port = port; return this; }
        public Builder deck(Deck deck) { this.deck = deck; return this; }
        public Builder firstPlayer(FirstMove firstMove) { this.firstPlayer = firstMove; return this; }
        public Builder joinVersion(int version) { this.joinVersion = version; return this; }
        public Builder joinPassword(String password) { this.joinPassword = password; return this; }
        public Builder createRoom(RoomOptions options) { this.createRoom = true; this.roomOptions = options; return this; }
        public Builder cardResolver(CardInfoResolver resolver) { this.cardResolver = resolver; return this; }
        public Builder fallbackPolicy(FallbackPolicy policy) { this.fallbackPolicy = policy; return this; }
        public Builder choiceTimeout(Duration timeout) { this.choiceTimeout = timeout; return this; }
        public Builder answerDelayMillis(int millis) { this.answerDelayMillis = millis; return this; }
        public Builder retryLimit(int limit) { this.retryLimit = limit; return this; }
        public Builder duelLog(Path jsonlPath) { this.duelLogPath = jsonlPath; return this; }
        public Builder duelLogRawFrames(boolean raw) { this.duelLogRawFrames = raw; return this; }
        public Builder duelLogDecisionContext(boolean enabled) { this.duelLogDecisionContext = enabled; return this; }

        public SessionConfig build() {
            return new SessionConfig(host, port, playerName, deck, firstPlayer, joinVersion,
                    joinPassword, createRoom, roomOptions, cardResolver, fallbackPolicy,
                    choiceTimeout, answerDelayMillis, retryLimit, duelLogPath, duelLogRawFrames,
                    duelLogDecisionContext);
        }
    }
}
