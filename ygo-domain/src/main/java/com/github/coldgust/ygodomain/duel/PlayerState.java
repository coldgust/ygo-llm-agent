package com.github.coldgust.ygodomain.duel;

import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.zone.ZoneRef;

import java.util.List;

/**
 * 一名玩家在镜像中的可见状态（我方视角下对方的手牌只含未知卡）。
 * 场上区域的卡通过 {@link DuelState} 的按区域查询获得（卡实例统一存放在 DuelState）。
 *
 * @param side              相对视角
 * @param seat              绝对座位号（0 / 1；调试与回放用）
 * @param name              玩家名
 * @param lifePoints        当前 LP
 * @param hand              手牌（对方版本全部为未知卡；协议洗牌时会置零，顺序不可靠）
 * @param mainDeckCount     主卡组剩余张数
 * @param extraDeckCount    额外卡组剩余张数
 * @param graveyard         墓地（公开，按放置顺序，先放置在前）
 * @param banished          除外区域（公开）
 * @param extraDeck         额外卡组（对方版本为未知卡）
 */
public record PlayerState(
        PlayerSide side,
        int seat,
        String name,
        int lifePoints,
        List<CardInstance> hand,
        int mainDeckCount,
        int extraDeckCount,
        List<CardIdentity> graveyard,
        List<CardIdentity> banished,
        List<CardIdentity> extraDeck
) {
    public PlayerState {
        hand = hand == null ? List.of() : List.copyOf(hand);
        graveyard = graveyard == null ? List.of() : List.copyOf(graveyard);
        banished = banished == null ? List.of() : List.copyOf(banished);
        extraDeck = extraDeck == null ? List.of() : List.copyOf(extraDeck);
    }

    /** 手牌张数（隐藏信息只看得到张数）。 */
    public int handCount() {
        return hand.size();
    }

    /** 手牌中未知卡的数量（对对方视角即"看不清的手牌数"）。 */
    public long unknownHandCount() {
        return hand.stream().filter(c -> c.identity().isUnknown()).count();
    }

    /** 查询手牌第 sequence 张。 */
    public CardInstance handAt(int sequence) {
        return hand.get(sequence);
    }

    /** 该玩家是否拥有指定的区域格子。 */
    public boolean owns(ZoneRef zone) {
        return zone.owner() == side;
    }
}
