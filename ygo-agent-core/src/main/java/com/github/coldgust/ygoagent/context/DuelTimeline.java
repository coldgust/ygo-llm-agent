package com.github.coldgust.ygoagent.context;

import com.github.coldgust.ygodomain.zone.ZoneRef;

import java.util.List;

/**
 * 决斗过程记录的窄端口：把"对局里发生过什么"以条目流供给上下文渲染。
 *
 * <p>core 不依赖 ygo-duel-log：由组装根（ygo-play）用适配器把 {@code session.duelLog()}
 * 的事件流翻译成本接口的条目，数据全用 core/domain 自己的类型。渲染在
 * {@link DuelRecordSections}；本接口只定义"取哪些、什么形状"。
 *
 * <p>条目是<b>已发生的事实</b>，可能比当前 {@code DuelState} 落后一两个事件
 * （记录异步落盘）：渲染层必须声明"记录与 [局面] 冲突时以局面为准"。
 * 另外，协议只对我方下发决策请求，对方回合的请求不可见——对方的动作只能
 * 以状态变化（卡移动/LP/发动）的形式出现在条目里。
 */
public interface DuelTimeline {

    /** seq 之后（不含）的条目，按 seq 升序（增量拉取用）。 */
    List<Entry> since(long seq);

    /** 某回合的全部条目（回合号与协议回合数一致，从 1 起）。 */
    List<Entry> turn(int turnNumber);

    /** 最新条目 seq；空记录为 0。 */
    long latestSeq();

    /** 动作方（观察者相对视角）。 */
    enum Side { SELF, OPPONENT, SYSTEM }

    /**
     * 条目：定位三件套（seq/turn/side）+ 按子类型取内容。
     * 与 ygo-duel-log 的事件词表保持同构，但不泄漏其类型。
     */
    sealed interface Entry {

        long seq();

        int turn();

        Side side();

        record TurnStarted(long seq, int turn, Side side) implements Entry {
        }

        /** phase 为官方阶段枚举名（如 MAIN_PHASE_1），渲染层负责翻译，解不出原样保留。 */
        record PhaseEntered(long seq, int turn, Side side, String phase) implements Entry {
        }

        /**
         * 一次卡移动（含抽卡/上场/进墓/除外/表示形式变化）。
         * passcode = 0 表示身份不可见（对手的手牌与盖放内容被协议抹零）；
         * position 为协议 Position 枚举名，可空。
         */
        record CardMoved(long seq, int turn, Side side, long passcode,
                         ZoneRef fromZone, ZoneRef toZone, String position) implements Entry {
        }

        /** 效果发动（入链，双方都有）。description 是效果描述码，渲染必须经 CardText 解条款。 */
        record EffectActivated(long seq, int turn, Side side, long passcode, long description) implements Entry {
        }

        /** 攻击宣言（记录补齐前可能没有发射者）。 */
        record AttackDeclared(long seq, int turn, Side side, long passcode, String target) implements Entry {
        }

        /** LP 变动（delta 负 = 受伤 / 支付代价）。 */
        record LpChanged(long seq, int turn, Side side, int delta, int lpAfter) implements Entry {
        }

        /** 我方已提交的决策（渲染默认过滤：我方动作由决策记忆与规划器对话承担，不重复给）。 */
        record DecisionMade(long seq, int turn, Side side, String requestType,
                            String summary, String source) implements Entry {
        }
    }
}
