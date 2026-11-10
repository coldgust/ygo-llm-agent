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
                 ProtocolIds.MSG_REVERSE_DECK, ProtocolIds.MSG_CHAIN_END,
                 ProtocolIds.MSG_CHAIN_NEGATED, ProtocolIds.MSG_CHAIN_DISABLED,
                 ProtocolIds.MSG_DAMAGE_STEP_START, ProtocolIds.MSG_DAMAGE_STEP_END -> 2;
            case ProtocolIds.MSG_HINT -> 7;      // type+player+data
            case ProtocolIds.MSG_START -> 19;
            case ProtocolIds.MSG_WIN -> 3;
            case ProtocolIds.MSG_SELECT_YESNO -> 6;      // type+player+desc4（实测截断修复）
            case ProtocolIds.MSG_SELECT_EFFECTYN -> 14;
            case ProtocolIds.MSG_SELECT_OPTION -> 3 + 4 * b[off + 2];
            case ProtocolIds.MSG_SELECT_CARD, ProtocolIds.MSG_SELECT_TRIBUTE -> 6 + 8 * u(b, off + 5);
            case ProtocolIds.MSG_SELECT_PLACE, ProtocolIds.MSG_SELECT_DISFIELD -> 7;
            case ProtocolIds.MSG_SELECT_POSITION -> 7;
            case ProtocolIds.MSG_SELECT_CHAIN -> chainSize(b, off);
            case ProtocolIds.MSG_SELECT_COUNTER -> 7 + 9 * u(b, off + 6);
            case ProtocolIds.MSG_SORT_CARD -> 3 + 8 * u(b, off + 2);
            case ProtocolIds.MSG_SELECT_BATTLECMD -> battleSize(b, off);
            case ProtocolIds.MSG_SELECT_IDLECMD -> idleSize(b, off);
            case ProtocolIds.MSG_SELECT_SUM -> sumSize(b, off);
            case ProtocolIds.MSG_SELECT_UNSELECT_CARD -> unselectSize(b, off);
            case ProtocolIds.MSG_CONFIRM_DECKTOP -> 3 + 7 * u(b, off + 2);
            case ProtocolIds.MSG_CONFIRM_CARDS -> confirmCardsSize(b, off);
            case ProtocolIds.MSG_SHUFFLE_HAND, ProtocolIds.MSG_SHUFFLE_EXTRA -> 3 + 4 * u(b, off + 2);
            case ProtocolIds.MSG_SWAP_GRAVE_DECK -> 3;
            case ProtocolIds.MSG_SHUFFLE_SET_CARD -> 3 + 8 * u(b, off + 2);
            case ProtocolIds.MSG_DECK_TOP -> 6;
            case ProtocolIds.MSG_NEW_TURN -> 3;
            case ProtocolIds.MSG_NEW_PHASE -> 4;
            case ProtocolIds.MSG_CONFIRM_EXTRATOP -> 3 + 7 * u(b, off + 2);
            case ProtocolIds.MSG_MOVE -> 17;
            case ProtocolIds.MSG_POS_CHANGE -> 10;
            case ProtocolIds.MSG_SET, ProtocolIds.MSG_SWAP, ProtocolIds.MSG_FIELD_DISABLED -> 9;
            case ProtocolIds.MSG_SUMMONING, ProtocolIds.MSG_SUMMONED,
                 ProtocolIds.MSG_SPSUMMONING, ProtocolIds.MSG_SPSUMMONED,
                 ProtocolIds.MSG_FLIPSUMMONING, ProtocolIds.MSG_FLIPSUMMONED -> 9;
            case ProtocolIds.MSG_CHAINING -> 17;
            case ProtocolIds.MSG_CHAINED, ProtocolIds.MSG_CHAIN_SOLVING, ProtocolIds.MSG_CHAIN_SOLVED -> 3;
            case ProtocolIds.MSG_CARD_SELECTED, ProtocolIds.MSG_RANDOM_SELECTED -> 3 + 4 * u(b, off + 2);
            case ProtocolIds.MSG_BECOME_TARGET -> 2 + 4 * u(b, off + 1);
            case ProtocolIds.MSG_DRAW -> 3 + 4 * u(b, off + 2);
            case ProtocolIds.MSG_DAMAGE, ProtocolIds.MSG_RECOVER,
                 ProtocolIds.MSG_LPUPDATE, ProtocolIds.MSG_PAY_LPCOST -> 6;
            case ProtocolIds.MSG_EQUIP, ProtocolIds.MSG_UNEQUIP,
                 ProtocolIds.MSG_CARD_TARGET, ProtocolIds.MSG_CANCEL_TARGET -> 9;
            case ProtocolIds.MSG_ADD_COUNTER, ProtocolIds.MSG_REMOVE_COUNTER -> 10;
            case ProtocolIds.MSG_ATTACK -> 9;
            case ProtocolIds.MSG_BATTLE -> 27;      // 26 字节 payload，无目标时后半段全 0
            case ProtocolIds.MSG_ATTACK_DISABLED -> 5;
            case ProtocolIds.MSG_MISSED_EFFECT -> 8;
            case ProtocolIds.MSG_TOSS_COIN, ProtocolIds.MSG_TOSS_DICE -> n < 3 ? -1 : 3 + u(b, off + 2);
            case ProtocolIds.MSG_ROCK_PAPER_SCISSORS, 133 -> 2;      // 133 = HAND_RES（无常量）
            case ProtocolIds.MSG_ANNOUNCE_RACE, ProtocolIds.MSG_ANNOUNCE_ATTRIB -> 6;
            case ProtocolIds.MSG_ANNOUNCE_CARD -> n >= 3 ? 3 + 4 * u(b, off + 2) : 5;      // 候选为空时仅 5 字节
            case ProtocolIds.MSG_ANNOUNCE_NUMBER -> 3 + 4 * u(b, off + 2);
            case ProtocolIds.MSG_CARD_HINT -> 8;
            case ProtocolIds.MSG_AI_NAME -> 2 + 4 * u(b, off + 1);
            case ProtocolIds.MSG_SHOW_HINT, ProtocolIds.MSG_PLAYER_HINT -> 5;
            case ProtocolIds.MSG_MATCH_KILL -> 5;
            default -> -1;       // 6/7/161/162/180 等未建模类型：整帧交付
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
