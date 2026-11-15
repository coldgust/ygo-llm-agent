package com.github.coldgust.ygoplayserver.card;

import com.github.coldgust.ygoagent.knowledge.CardFact;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * 卡牌事实表 {@code card} 的只读实体。
 *
 * <p>行由 ingest 脚本({@code make cards-pg})用 COPY 写入,Java 侧永不写——因此映射为
 * {@link Immutable},并把「行 → 领域对象」的推导({@code kind} 分类、灵摆/怪兽效果合并、
 * NULL 归一)从原 {@code PgCardFacts} 迁到这里,适配器只留缓存与降级。
 */
@Entity
@Immutable
@Table(name = "card")
public class CardEntity {

    @Id
    private Long passcode;

    @Column(nullable = false)
    private String name;

    @Column(name = "name_en")
    private String nameEn;

    @Column(name = "card_type")
    private String cardType;

    private String race;

    private String attribute;

    private Integer atk;

    @Column(name = "def")
    private Integer defense;

    private Integer level;

    @Column(name = "monster_effect")
    private String monsterEffect;

    @Column(name = "pendulum_effect")
    private String pendulumEffect;

    protected CardEntity() {
        // JPA
    }

    /** 行 → core 卡牌事实。kind 由 card_type 推导,不落库(与 cards.jsonl 口径一致)。 */
    public CardFact toCardFact() {
        String type = nullToEmpty(cardType);
        return new CardFact(
                passcode == null ? 0L : passcode,
                name,
                nullToEmpty(nameEn),
                kindOf(type),
                type,
                nullToEmpty(race),
                nullToEmpty(attribute),
                atk,
                defense,
                level,
                mergeEffect(monsterEffect, pendulumEffect));
    }

    private static String mergeEffect(String monster, String pendulum) {
        boolean hasPendulum = pendulum != null && !pendulum.isBlank();
        boolean hasMonster = monster != null && !monster.isBlank();
        if (hasPendulum && hasMonster) {
            return "【灵摆】" + pendulum + "\n【怪兽】" + monster;
        }
        if (hasPendulum) {
            return "【灵摆】" + pendulum;
        }
        return hasMonster ? monster : "";
    }

    private static String kindOf(String cardType) {
        if (cardType == null) {
            return "UNKNOWN";
        }
        if (cardType.contains("怪兽")) {
            return "MONSTER";
        }
        if (cardType.contains("陷阱")) {
            return "TRAP";
        }
        if (cardType.contains("魔法")) {
            return "SPELL";
        }
        return "UNKNOWN";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}