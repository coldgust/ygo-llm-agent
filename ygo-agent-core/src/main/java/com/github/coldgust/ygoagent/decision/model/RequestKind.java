package com.github.coldgust.ygoagent.decision.model;

import com.github.coldgust.ygodomain.decision.ChoiceRequest;

/** 19 类决策请求的枚举化类型（与 ChoiceRequest 子类型一一对应）。 */
public enum RequestKind {
    MAIN_PHASE("主要阶段"),
    BATTLE_PHASE("战斗阶段"),
    CHAIN("连锁响应"),
    CARD_SELECT("选择卡片"),
    TRIBUTE_SELECT("选择祭品"),
    SUM("凑数选择"),
    UNSELECT("取消选择"),
    PLACE("选择位置"),
    DISFIELD("选择场地区域"),
    POSITION("表示形式"),
    EFFECT_YES_NO("是否发动"),
    YES_NO("是否确认"),
    OPTION("选项选择"),
    DECLARE_RACE("宣言种族"),
    DECLARE_ATTRIBUTE("宣言属性"),
    DECLARE_CARD("宣言卡片"),
    DECLARE_NUMBER("宣言数值"),
    SORT("排序"),
    COUNTER("连锁计数");

    private final String label;

    RequestKind(String label) {
        this.label = label;
    }

    /** 中文短标签（前端 / 日志用，避免把 DA_* 协议词直接甩给使用者）。 */
    public String label() {
        return label;
    }

    public static RequestKind of(ChoiceRequest request) {
        return switch (request) {
            case ChoiceRequest.MainPhaseRequest ignored -> MAIN_PHASE;
            case ChoiceRequest.BattlePhaseRequest ignored -> BATTLE_PHASE;
            case ChoiceRequest.ChainRequest ignored -> CHAIN;
            case ChoiceRequest.CardSelectRequest ignored -> CARD_SELECT;
            case ChoiceRequest.TributeSelectRequest ignored -> TRIBUTE_SELECT;
            case ChoiceRequest.SumRequest ignored -> SUM;
            case ChoiceRequest.UnselectRequest ignored -> UNSELECT;
            case ChoiceRequest.PlaceRequest ignored -> PLACE;
            case ChoiceRequest.DisfieldRequest ignored -> DISFIELD;
            case ChoiceRequest.PositionRequest ignored -> POSITION;
            case ChoiceRequest.EffectYesNoRequest ignored -> EFFECT_YES_NO;
            case ChoiceRequest.YesNoRequest ignored -> YES_NO;
            case ChoiceRequest.OptionRequest ignored -> OPTION;
            case ChoiceRequest.DeclareRaceRequest ignored -> DECLARE_RACE;
            case ChoiceRequest.DeclareAttributeRequest ignored -> DECLARE_ATTRIBUTE;
            case ChoiceRequest.DeclareCardRequest ignored -> DECLARE_CARD;
            case ChoiceRequest.DeclareNumberRequest ignored -> DECLARE_NUMBER;
            case ChoiceRequest.SortRequest ignored -> SORT;
            case ChoiceRequest.CounterRequest ignored -> COUNTER;
        };
    }
}
