package com.github.coldgust.ygoprosdk.spi;

import com.github.coldgust.ygodomain.card.CardDefinition;

import com.github.coldgust.ygodomain.card.CardIdentity;
import java.util.Optional;

/**
 * 卡牌定义解析 SPI（规范 §10）：协议只携带 passcode，
 * 卡名 / 种类 / 属性等静态定义由调用方注入（Agent 侧通常接 data/cards/cards.jsonl 的卡牌精确查询）。
 *
 * <p>SDK 对查不到的卡仅携带 passcode（{@link CardIdentity.Known}
 * 的 definition 为 null），不阻塞协议流程。
 */
@FunctionalInterface
public interface CardInfoResolver {

    Optional<CardDefinition> resolve(long passcode);
}
