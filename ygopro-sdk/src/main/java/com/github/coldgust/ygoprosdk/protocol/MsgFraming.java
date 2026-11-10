package com.github.coldgust.ygoprosdk.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code STOC_GAME_MSG} 内层 MSG 流的切分器（规范 §7.1）。
 *
 * <p>规则：
 * <ol>
 *   <li>每种 MSG 有长度模型（固定 / 由 payload 计数字段推导）；</li>
 *   <li>未知类型：把本帧剩余部分整体作为一条不透明消息（YGOPro 系服务端会发送部分实现已废弃的
 *       {@code MSG_UPDATE_DATA}，其布局已在规范 §9.4.4 定义；此处整段交付给解码层）；</li>
 *   <li>结构校验：错误对齐能"恰好凑满一帧"且不报错，对已知请求做形态检查提前剪掉。</li>
 * </ol>
 */
public final class MsgFraming {
    private MsgFraming() {
    }

    /** 一条切分出的消息：type 与其在 payload 中的起止（含 type 字节）。 */
    public record Msg(int type, int start, int end) {
        public int size() {
            return end - start;
        }
    }

    /**
     * 切分整条 STOC_GAME_MSG payload。
     *
     * @return 全部消息（含末尾未解完的不透明段）
     */
    public static List<Msg> split(byte[] payload) {
        List<Msg> out = new ArrayList<>();
        int off = 0;
        while (off < payload.length) {
            int type = payload[off] & 0xFF;
            int total;
            try {
                total = totalSize(type, payload, off);
            } catch (IndexOutOfBoundsException e) {
                // 长度模型读取越界 = 误对齐，按未知消息处理（规范 §10.3.2 的防线之一）
                total = -1;
            }
            if (total < 0 || off + total > payload.length) {
                // 未知 / 不完整模型：剩余部分整体视为一条消息
                out.add(new Msg(type, off, payload.length));
                break;
            }
            out.add(new Msg(type, off, off + total));
            off += total;
        }
        return out;
    }

    /**
     * 某消息的总字节数（含 type 字节）。返回 -1 表示未知（变长且无法从本地字段推导）。
     */
    public static int totalSize(int type, byte[] b, int off) {
        int n = b.length - off;
        if (n < 1) {
            return -1;
        }
        return switch (type) {
            case ProtocolIds.MSG_RETRY, ProtocolIds.MSG_WAITING,
                 ProtocolIds.MSG_REQUEST_DECK, ProtocolIds.MSG_SHUFFLE_DECK,
                 ProtocolIds.MSG_REVERSE_DECK, 74, 75, 76, // CHAIN_END / NEGATED / DISABLED
                 113, 114 -> 2; // DAMAGE_STEP_START / END
            case 2 -> 7;      // HINT: type+player+data
            case 4 -> 19;     // START
            case 5 -> 3;      // WIN
            case 13 -> 6;      // SELECT_YESNO: type+player+desc4（实测截断修复）
            case 12 -> 14;    // SELECT_EFFECTYN
            case 14 -> 3 + 4 * b[off + 2];                       // SELECT_OPTION
            case 15, 20 -> 6 + 8 * u(b, off + 5);                // SELECT_CARD / TRIBUTE
            case 18, 24 -> 7;                                    // SELECT_PLACE / DISFIELD
            case 19 -> 7;                                        // SELECT_POSITION
            case 16 -> chainSize(b, off);                        // SELECT_CHAIN
            case 22 -> 7 + 9 * u(b, off + 6);                    // SELECT_COUNTER
            case 25 -> 3 + 8 * u(b, off + 2);                    // SORT_CARD
            case 10 -> battleSize(b, off);                       // SELECT_BATTLECMD
            case 11 -> idleSize(b, off);                         // SELECT_IDLECMD
            case 23 -> sumSize(b, off);                          // SELECT_SUM
            case 26 -> unselectSize(b, off);                     // SELECT_UNSELECT_CARD
            case 30 -> 3 + 7 * u(b, off + 2);                    // CONFIRM_DECKTOP
            case 31 -> confirmCardsSize(b, off);                 // CONFIRM_CARDS
            case 33, 39 -> 3 + 4 * u(b, off + 2);                // SHUFFLE_HAND / SHUFFLE_EXTRA
            case 35 -> 3;                                        // SWAP_GRAVE_DECK
            case 36 -> 3 + 8 * u(b, off + 2);                    // SHUFFLE_SET_CARD
            case 38 -> 6;                                        // DECK_TOP
            case 40 -> 3;                                        // NEW_TURN
            case 41 -> 4;                                        // NEW_PHASE
            case 42 -> 3 + 7 * u(b, off + 2);                    // CONFIRM_EXTRATOP
            case 50 -> 17;                                       // MOVE
            case 53 -> 10;                                       // POS_CHANGE
            case 54, 55, 56 -> 9;                                // SET / SWAP / FIELD_DISABLED
            case 60, 61, 62, 63, 64, 65 -> 9;                    // 召唤族
            case 70 -> 17;                                       // CHAINING
            case 71, 72, 73 -> 3;                                // CHAINED / SOLVING / SOLVED
            case 80, 81 -> 3 + 4 * u(b, off + 2);                // CARD_SELECTED / RANDOM_SELECTED
            case 83 -> 2 + 4 * u(b, off + 1);                    // BECOME_TARGET
            case 90 -> 3 + 4 * u(b, off + 2);                    // DRAW
            case 91, 92, 94, 100 -> 6;                           // DAMAGE / RECOVER / LPUPDATE / PAY_LPCOST
            case 93, 95, 96, 97 -> 9;                            // EQUIP / UNEQUIP / TARGET / CANCEL_TARGET
            case 101, 102 -> 10;                                 // ADD/REMOVE_COUNTER
            case 110 -> 9;                                       // ATTACK
            case 111 -> 27;                                      // BATTLE（26 字节 payload，无目标时后半段全 0）
            case 112 -> 5;                                       // ATTACK_DISABLED
            case 120 -> 8;                                       // MISSED_EFFECT
            case 130, 131 -> n < 3 ? -1 : 3 + u(b, off + 2);     // TOSS_COIN / TOSS_DICE
            case 132, 133 -> 2;                                  // RPS / HAND_RES
            case 140, 141 -> 6;                                  // ANNOUNCE_RACE / ATTRIB
            case 142 -> n >= 3 ? 3 + 4 * u(b, off + 2) : 5;      // ANNOUNCE_CARD（候选为空时仅 5 字节）
            case 143 -> 3 + 4 * u(b, off + 2);                   // ANNOUNCE_NUMBER
            case 160 -> 8;                                       // CARD_HINT
            case 163 -> 2 + 4 * u(b, off + 1);                   // AI_NAME
            case 164, 165 -> 5;                                  // SHOW_HINT / PLAYER_HINT
            case 170 -> 5;                                       // MATCH_KILL
            default -> -1;                                       // 6 / 7 / 161 / 162 / 180 等：整帧交付
        };
    }

    private static int u(byte[] b, int off) {
        return b[off] & 0xFF;
    }

    /**
     * SELECT_CHAIN：头部 12（type + player + count + speCount + hint×2）+ 每条 14。
     */
    private static int chainSize(byte[] b, int off) {
        return 12 + 14 * u(b, off + 2);
    }

    /** SELECT_BATTLECMD：头部 4 + 11·可发动数 + 8·可攻击数 + 2 标志。 */
    private static int battleSize(byte[] b, int off) {
        return 4 + 11 * u(b, off + 2) + 8 * u(b, off + 3 + 11 * u(b, off + 2)) + 2;
    }

    /** SELECT_IDLECMD：头部 2 + 7·(组1..5) + 11·组6 + 3 标志。 */
    private static int idleSize(byte[] b, int off) {
        int p = off + 2;
        int total = 2;
        for (int g = 0; g < 5; g++) {
            int cnt = u(b, p);
            total += 1 + 7 * cnt;
            p += 1 + 7 * cnt;
        }
        int cnt6 = u(b, p);
        total += 1 + 11 * cnt6;
        p += 1 + 11 * cnt6;
        total += 3; // toBP / toEP / canShuffle
        return total;
    }

    /** SELECT_SUM：type+mode+player+acc4+min+max = 9，nm 在 off+9；总长 10 + 11(m+o)（对齐 core 写入序）。 */
    private static int sumSize(byte[] b, int off) {
        int m = u(b, off + 9);
        int o = u(b, off + 10 + 11 * m);
        return 10 + 11 * (m + o);
    }

    /** SELECT_UNSELECT_CARD：头部 7 + 8(n1+n2)。 */
    private static int unselectSize(byte[] b, int off) {
        int n1 = u(b, off + 6);
        return 8 + 8 * n1 + 8 * u(b, off + 7 + 8 * n1);
    }

    /** CONFIRM_CARDS：头部 4（player + skipPanel + count）+ 7·count。 */
    private static int confirmCardsSize(byte[] b, int off) {
        return 4 + 7 * u(b, off + 3);
    }


    /**
     * 已知请求的结构形态检查；返回 false 表示切分结果不可信（误对齐）。
     */
    public static boolean plausible(int type, byte[] b, int start, int end) {
        int n = end - start;
        return switch (type) {
            case 4 -> n == 19 && (u(b, start + 1) <= 1) && u(b, start + 2) <= 5;
            case 18, 24 -> n == 7 && u(b, start + 2) <= 7;
            case 15, 20 -> n == 6 + 8 * u(b, start + 5);
            case 16 -> {
                int cnt = u(b, start + 2);
                yield n == 12 + 14 * cnt;
            }
            case 11 -> n == idleSize(b, start);
            case 26 -> n == 8 + 8 * (u(b, start + 6) + u(b, start + 7 + 8 * u(b, start + 6)));
            default -> true;
        };
    }
}
