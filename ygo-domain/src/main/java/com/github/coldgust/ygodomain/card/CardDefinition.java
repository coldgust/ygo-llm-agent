package com.github.coldgust.ygodomain.card;

import java.util.Set;

/**
 * 卡片的静态定义（卡片库中的"这一张卡"），与场上实例无关。
 * 全部字段来自卡牌数据库 / 知识模块；协议只携带 passcode，定义由调用方注入。
 *
 * @param passcode       卡片密码（官方 passcode，唯一）
 * @param alias          同名马甲卡的代表密码（0 表示无马甲）
 * @param name           官方英文卡名（如 "Blue-Eyes White Dragon"）
 * @param kind           卡片大类（Monster / Spell / Trap）
 * @param monsterTypes   怪兽种类与能力标志集合（MONSTER 时非空）
 * @param spellType      魔法种类（SPELL 时非空）
 * @param trapType       陷阱种类（TRAP 时非空）
 * @param level          等级（怪兽 1–12；连接怪兽无等级，为 null）
 * @param rank           阶级（超量怪兽）
 * @param linkRating     连接标记数（连接怪兽 = Link Rating）
 * @param attack         攻击力（? 为 null；连接怪兽恒 null）
 * @param defense        守备力（连接怪兽恒 null）
 * @param pendulumScale  灵摆刻度（Pendulum Scale；灵摆怪兽 1–13，其他为 null）
 * @param linkArrows     连接标记方向集合（仅连接怪兽非空）
 */
public record CardDefinition(
        long passcode,
        long alias,
        String name,
        CardKind kind,
        Set<MonsterType> monsterTypes,
        SpellType spellType,
        TrapType trapType,
        Attribute attribute,
        MonsterRace race,
        Integer level,
        Integer rank,
        Integer linkRating,
        Integer attack,
        Integer defense,
        Integer pendulumScale,
        Set<LinkArrow> linkArrows,
        Set<EffectCategory> effectCategories
) {
    public CardDefinition {
        monsterTypes = monsterTypes == null ? Set.of() : Set.copyOf(monsterTypes);
        linkArrows = linkArrows == null ? Set.of() : Set.copyOf(linkArrows);
        effectCategories = effectCategories == null ? Set.of() : Set.copyOf(effectCategories);
    }

    public boolean isMonster() {
        return kind == CardKind.MONSTER;
    }

    public boolean isSpell() {
        return kind == CardKind.SPELL;
    }

    public boolean isTrap() {
        return kind == CardKind.TRAP;
    }

    /** 是否为额外卡组怪兽（融合 / 同调 / 超量 / 连接）。 */
    public boolean isExtraDeckMonster() {
        return isMonster() && monsterTypes.stream().anyMatch(MonsterType::isExtraDeckKind);
    }

    public boolean isPendulumMonster() {
        return isMonster() && monsterTypes.contains(MonsterType.PENDULUM);
    }

    /** 是否为连接怪兽（无守备力、不能守备表示）。 */
    public boolean isLinkMonster() {
        return isMonster() && monsterTypes.contains(MonsterType.LINK);
    }

    public boolean isTuner() {
        return isMonster() && monsterTypes.contains(MonsterType.TUNER);
    }

    public static Builder builder(long passcode, String name, CardKind kind) {
        return new Builder(passcode, name, kind);
    }

    /** 链式构建器：绝大多数字段可缺省。 */
    public static final class Builder {
        private final long passcode;
        private final String name;
        private final CardKind kind;
        private long alias;
        private Set<MonsterType> monsterTypes = Set.of();
        private SpellType spellType;
        private TrapType trapType;
        private Attribute attribute;
        private MonsterRace race;
        private Integer level;
        private Integer rank;
        private Integer linkRating;
        private Integer attack;
        private Integer defense;
        private Integer pendulumScale;
        private Set<LinkArrow> linkArrows = Set.of();
        private Set<EffectCategory> effectCategories = Set.of();

        private Builder(long passcode, String name, CardKind kind) {
            this.passcode = passcode;
            this.name = name;
            this.kind = kind;
        }

        public Builder alias(long alias) { this.alias = alias; return this; }
        public Builder monsterTypes(Set<MonsterType> types) { this.monsterTypes = types; return this; }
        public Builder spellType(SpellType type) { this.spellType = type; return this; }
        public Builder trapType(TrapType type) { this.trapType = type; return this; }
        public Builder attribute(Attribute a) { this.attribute = a; return this; }
        public Builder race(MonsterRace r) { this.race = r; return this; }
        public Builder level(int level) { this.level = level; return this; }
        public Builder rank(int rank) { this.rank = rank; return this; }
        public Builder linkRating(int rating) { this.linkRating = rating; return this; }
        public Builder attack(int attack) { this.attack = attack; return this; }
        public Builder defense(int defense) { this.defense = defense; return this; }
        public Builder pendulumScale(int scale) { this.pendulumScale = scale; return this; }
        public Builder linkArrows(Set<LinkArrow> arrows) { this.linkArrows = arrows; return this; }
        public Builder effectCategories(Set<EffectCategory> categories) { this.effectCategories = categories; return this; }

        public CardDefinition build() {
            return new CardDefinition(passcode, alias, name, kind, monsterTypes, spellType, trapType,
                    attribute, race, level, rank, linkRating, attack, defense, pendulumScale,
                    linkArrows, effectCategories);
        }
    }
}
