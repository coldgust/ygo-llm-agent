package com.github.coldgust.ygoprosdk.protocol;

/**
 * 协议常量（依据 docs/ygopro-protocol-spec.md §4 / §7）。
 */
public final class ProtocolIds {
    private ProtocolIds() {
    }

    public static final int CTOS_RESPONSE = 0x01;
    public static final int CTOS_UPDATE_DECK = 0x02;
    public static final int CTOS_HAND_RESULT = 0x03;
    public static final int CTOS_TP_RESULT = 0x04;
    public static final int CTOS_PLAYER_INFO = 0x10;
    public static final int CTOS_CREATE_GAME = 0x11;
    public static final int CTOS_JOIN_GAME = 0x12;
    public static final int CTOS_LEAVE_GAME = 0x13;
    public static final int CTOS_SURRENDER = 0x14;
    public static final int CTOS_TIME_CONFIRM = 0x15;
    public static final int CTOS_CHAT = 0x16;
    public static final int CTOS_HS_TODUELIST = 0x20;
    public static final int CTOS_HS_TOOBSERVER = 0x21;
    public static final int CTOS_HS_READY = 0x22;
    public static final int CTOS_HS_NOTREADY = 0x23;
    public static final int CTOS_HS_KICK = 0x24;
    public static final int CTOS_HS_START = 0x25;
    public static final int CTOS_REQUEST_FIELD = 0x30;

    public static final int STOC_GAME_MSG = 0x01;
    public static final int STOC_ERROR_MSG = 0x02;
    public static final int STOC_SELECT_HAND = 0x03;
    public static final int STOC_SELECT_TP = 0x04;
    public static final int STOC_HAND_RESULT = 0x05;
    public static final int STOC_CHANGE_SIDE = 0x07;
    public static final int STOC_WAITING_SIDE = 0x08;
    public static final int STOC_DECK_COUNT = 0x09;
    public static final int STOC_JOIN_GAME = 0x12;
    public static final int STOC_TYPE_CHANGE = 0x13;
    public static final int STOC_LEAVE_GAME = 0x14;
    public static final int STOC_DUEL_START = 0x15;
    public static final int STOC_DUEL_END = 0x16;
    public static final int STOC_REPLAY = 0x17;
    public static final int STOC_TIME_LIMIT = 0x18;
    public static final int STOC_CHAT = 0x19;
    public static final int STOC_HS_PLAYER_ENTER = 0x20;
    public static final int STOC_HS_PLAYER_CHANGE = 0x21;
    public static final int STOC_HS_WATCH_CHANGE = 0x22;
    public static final int STOC_FIELD_FINISH = 0x30;

    public static final int ERROR_JOIN = 1;
    public static final int ERROR_DECK = 2;
    public static final int ERROR_SIDE = 3;
    public static final int ERROR_VER = 4;
    public static final int ERROR_VER2 = 5;

    public static final int MSG_RETRY = 1;
    public static final int MSG_HINT = 2;
    public static final int MSG_WAITING = 3;
    public static final int MSG_START = 4;
    public static final int MSG_WIN = 5;
    public static final int MSG_UPDATE_DATA = 6;
    public static final int MSG_UPDATE_CARD = 7;
    public static final int MSG_SELECT_BATTLECMD = 10;
    public static final int MSG_SELECT_IDLECMD = 11;
    public static final int MSG_SELECT_EFFECTYN = 12;
    public static final int MSG_SELECT_YESNO = 13;
    public static final int MSG_SELECT_OPTION = 14;
    public static final int MSG_SELECT_CARD = 15;
    public static final int MSG_SELECT_CHAIN = 16;
    public static final int MSG_SELECT_PLACE = 18;
    public static final int MSG_SELECT_POSITION = 19;
    public static final int MSG_SELECT_TRIBUTE = 20;
    public static final int MSG_SELECT_COUNTER = 22;
    public static final int MSG_SELECT_SUM = 23;
    public static final int MSG_SELECT_DISFIELD = 24;
    public static final int MSG_SORT_CARD = 25;
    public static final int MSG_SELECT_UNSELECT_CARD = 26;
    public static final int MSG_CONFIRM_DECKTOP = 30;
    public static final int MSG_CONFIRM_CARDS = 31;
    public static final int MSG_SHUFFLE_HAND = 33;
    public static final int MSG_NEW_TURN = 40;
    public static final int MSG_NEW_PHASE = 41;
    public static final int MSG_MOVE = 50;
    public static final int MSG_POS_CHANGE = 53;
    public static final int MSG_SET = 54;
    public static final int MSG_SWAP = 55;
    public static final int MSG_SUMMONING = 60;
    public static final int MSG_SUMMONED = 61;
    public static final int MSG_SPSUMMONING = 62;
    public static final int MSG_SPSUMMONED = 63;
    public static final int MSG_FLIPSUMMONING = 64;
    public static final int MSG_FLIPSUMMONED = 65;
    public static final int MSG_CHAINING = 70;
    public static final int MSG_CHAINED = 71;
    public static final int MSG_CHAIN_SOLVING = 72;
    public static final int MSG_CHAIN_SOLVED = 73;
    public static final int MSG_CHAIN_END = 74;
    public static final int MSG_CHAIN_NEGATED = 75;
    public static final int MSG_CHAIN_DISABLED = 76;
    public static final int MSG_CARD_SELECTED = 80;
    public static final int MSG_RANDOM_SELECTED = 81;
    public static final int MSG_BECOME_TARGET = 83;
    public static final int MSG_DRAW = 90;
    public static final int MSG_DAMAGE = 91;
    public static final int MSG_RECOVER = 92;
    public static final int MSG_EQUIP = 93;
    public static final int MSG_LPUPDATE = 94;
    public static final int MSG_PAY_LPCOST = 100;
    public static final int MSG_ADD_COUNTER = 101;
    public static final int MSG_REMOVE_COUNTER = 102;
    public static final int MSG_ATTACK = 110;
    public static final int MSG_BATTLE = 111;
    public static final int MSG_MISSED_EFFECT = 120;
    public static final int MSG_TOSS_COIN = 130;
    public static final int MSG_TOSS_DICE = 131;
    public static final int MSG_ROCK_PAPER_SCISSORS = 132;
    public static final int MSG_ANNOUNCE_RACE = 140;
    public static final int MSG_ANNOUNCE_ATTRIB = 141;
    public static final int MSG_ANNOUNCE_CARD = 142;
    public static final int MSG_ANNOUNCE_NUMBER = 143;
    public static final int MSG_REQUEST_DECK = 8;
    public static final int MSG_SHUFFLE_DECK = 32;
    public static final int MSG_REFRESH_DECK = 34;
    public static final int MSG_SWAP_GRAVE_DECK = 35;
    public static final int MSG_SHUFFLE_SET_CARD = 36;
    public static final int MSG_REVERSE_DECK = 37;
    public static final int MSG_DECK_TOP = 38;
    public static final int MSG_SHUFFLE_EXTRA = 39;
    public static final int MSG_CONFIRM_EXTRATOP = 42;
    public static final int MSG_FIELD_DISABLED = 56;
    public static final int MSG_UNEQUIP = 95;
    public static final int MSG_CARD_TARGET = 96;
    public static final int MSG_CANCEL_TARGET = 97;
    public static final int MSG_ATTACK_DISABLED = 112;
    public static final int MSG_DAMAGE_STEP_START = 113;
    public static final int MSG_DAMAGE_STEP_END = 114;
    public static final int MSG_BE_CHAIN_TARGET = 121;
    public static final int MSG_CARD_HINT = 160;
    public static final int MSG_TAG_SWAP = 161;
    public static final int MSG_AI_NAME = 163;
    public static final int MSG_SHOW_HINT = 164;
    public static final int MSG_PLAYER_HINT = 165;
    public static final int MSG_MATCH_KILL = 170;
    public static final int MSG_RELOAD_FIELD = 162;

    /** 请求型消息集合（需要 CTOS_RESPONSE 回答）。 */
    public static boolean isRequest(int msgType) {
        return switch (msgType) {
            case MSG_SELECT_BATTLECMD, MSG_SELECT_IDLECMD, MSG_SELECT_EFFECTYN, MSG_SELECT_YESNO,
                 MSG_SELECT_OPTION, MSG_SELECT_CARD, MSG_SELECT_CHAIN, MSG_SELECT_PLACE,
                 MSG_SELECT_POSITION, MSG_SELECT_TRIBUTE, MSG_SELECT_COUNTER, MSG_SELECT_SUM,
                 MSG_SELECT_DISFIELD, MSG_SORT_CARD, MSG_SELECT_UNSELECT_CARD,
                 MSG_ANNOUNCE_RACE, MSG_ANNOUNCE_ATTRIB, MSG_ANNOUNCE_CARD, MSG_ANNOUNCE_NUMBER,
                 MSG_ROCK_PAPER_SCISSORS -> true;
            default -> false;
        };
    }

    /** 人类可读名称（日志用）。CTOS 与 STOC 编号重叠，按方向分别查名后合并。 */
    public static String name(int id) {
        String ctos = ctosName(id);
        String stoc = stocName(id);
        if (ctos != null && stoc != null) {
            return ctos + "/" + stoc;
        }
        if (ctos != null) {
            return ctos;
        }
        if (stoc != null) {
            return stoc;
        }
        return "0x%02X".formatted(id);
    }

    private static String ctosName(int id) {
        return switch (id) {
            case CTOS_RESPONSE -> "CTOS_RESPONSE";
            case CTOS_UPDATE_DECK -> "CTOS_UPDATE_DECK";
            case CTOS_HAND_RESULT -> "CTOS_HAND_RESULT";
            case CTOS_TP_RESULT -> "CTOS_TP_RESULT";
            case CTOS_PLAYER_INFO -> "CTOS_PLAYER_INFO";
            case CTOS_CREATE_GAME -> "CTOS_CREATE_GAME";
            case CTOS_JOIN_GAME -> "CTOS_JOIN_GAME";
            case CTOS_LEAVE_GAME -> "CTOS_LEAVE_GAME";
            case CTOS_SURRENDER -> "CTOS_SURRENDER";
            case CTOS_TIME_CONFIRM -> "CTOS_TIME_CONFIRM";
            case CTOS_CHAT -> "CTOS_CHAT";
            case CTOS_HS_TODUELIST -> "CTOS_HS_TODUELIST";
            case CTOS_HS_TOOBSERVER -> "CTOS_HS_TOOBSERVER";
            case CTOS_HS_READY -> "CTOS_HS_READY";
            case CTOS_HS_NOTREADY -> "CTOS_HS_NOTREADY";
            case CTOS_HS_KICK -> "CTOS_HS_KICK";
            case CTOS_HS_START -> "CTOS_HS_START";
            case CTOS_REQUEST_FIELD -> "CTOS_REQUEST_FIELD";
            default -> null;
        };
    }

    private static String stocName(int id) {
        return switch (id) {
            case STOC_GAME_MSG -> "STOC_GAME_MSG";
            case STOC_ERROR_MSG -> "STOC_ERROR_MSG";
            case STOC_SELECT_HAND -> "STOC_SELECT_HAND";
            case STOC_SELECT_TP -> "STOC_SELECT_TP";
            case STOC_HAND_RESULT -> "STOC_HAND_RESULT";
            case STOC_CHANGE_SIDE -> "STOC_CHANGE_SIDE";
            case STOC_WAITING_SIDE -> "STOC_WAITING_SIDE";
            case STOC_DECK_COUNT -> "STOC_DECK_COUNT";
            case STOC_JOIN_GAME -> "STOC_JOIN_GAME";
            case STOC_TYPE_CHANGE -> "STOC_TYPE_CHANGE";
            case STOC_DUEL_START -> "STOC_DUEL_START";
            case STOC_DUEL_END -> "STOC_DUEL_END";
            case STOC_REPLAY -> "STOC_REPLAY";
            case STOC_TIME_LIMIT -> "STOC_TIME_LIMIT";
            case STOC_CHAT -> "STOC_CHAT";
            case STOC_HS_PLAYER_ENTER -> "STOC_HS_PLAYER_ENTER";
            case STOC_HS_PLAYER_CHANGE -> "STOC_HS_PLAYER_CHANGE";
            case STOC_HS_WATCH_CHANGE -> "STOC_HS_WATCH_CHANGE";
            case STOC_FIELD_FINISH -> "STOC_FIELD_FINISH";
            default -> null;
        };
    }
}
