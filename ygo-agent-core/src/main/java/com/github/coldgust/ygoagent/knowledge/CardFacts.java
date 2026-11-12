package com.github.coldgust.ygoagent.knowledge;

import java.util.List;
import java.util.Optional;

/**
 * 卡牌事实查表接口（v6 §3.3 线 2：卡面永远走精确查表，不走语义检索）。
 */
public interface CardFacts {

    Optional<CardFact> byPasscode(long passcode);

    /** 查不到时返回"未知卡"占位事实（绝不让提示词里出现无来源的名字）。 */
    default CardFact orUnknown(long passcode) {
        return byPasscode(passcode).orElseGet(
                () -> new CardFact(passcode, "未知卡(" + passcode + ")", "", "UNKNOWN", "", "", "",
                        null, null, null, ""));
    }

    long knownCount();

    /**
     * 按卡名/英文名模糊搜索（忽略大小写，包含匹配）。
     *
     * <p>默认不支持（返回空）：只有建立了名字索引的实现才需要覆盖。
     * 给模型用的"查卡"工具依赖它把中文卡名换成卡号。
     */
    default List<CardFact> searchByName(String keyword, int limit) {
        return List.of();
    }
}
