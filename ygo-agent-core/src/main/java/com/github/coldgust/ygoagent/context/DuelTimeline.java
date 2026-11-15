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
         * passcode = 0 表示身份不可见（对手的手牌与盖放内容被协议抹零；
         * 例外是检索/亮牌——那由 MSG_CARD_SELECTED 回填，见 StateProjector）；
         * position 为协议 Position 枚举名，可空。
         * reason 为协议 MSG_MOVE reason 位的可读标签（如 "COST"、"EFFECT+DESTROY"；
         * null = 协议未给出）——「COST」渲染成〔代价〕，模型靠它分清进墓是效果结果还是已付的代价。
         */
        record CardMoved(long seq, int turn, Side side, long passcode,
                         ZoneRef fromZone, ZoneRef toZone, String position,
                         String reason) implements Entry {
            public CardMoved(long seq, int turn, Side side, long passcode,
                             ZoneRef fromZone, ZoneRef toZone, String position) {
                this(seq, turn, side, passcode, fromZone, toZone, position, null);
            }
        }

        /**
         * 连锁对象确定（MSG_BECOME_TARGET）：MDPRO3 的「对象」块——这一环要动谁。
         * chainIndex = 对象所属的连锁环（1 起；null = 链尚未建立，协议乱序）。
         */
        record ChainTargeted(long seq, int turn, Side side, Integer chainIndex,
                             List<Target> targets) implements Entry {
            public ChainTargeted {
                targets = targets == null ? List.of() : List.copyOf(targets);
            }

            /** 一个对象：所在区域 + 身份（解不出为 0，渲染"未知卡"）。 */
            public record Target(ZoneRef zone, long passcode) {
            }
        }

        /**
         * 效果发动（入链，双方都有）。description 是效果描述码，渲染必须经 CardText 解条款。
         * chainIndex = 这次发动是连锁的第几环（1 起；null/0 = 协议未给出）。
         * sourceZone = 发动来源区域（协议 triggering 字段；null = 未给出）——多条款卡
         * 解不出条款时靠它 + 全文让模型判断是哪一条（如 创造① 场上/手卡、② 墓地）。
         */
        record EffectActivated(long seq, int turn, Side side, long passcode, long description,
                               Integer chainIndex, ZoneRef sourceZone) implements Entry {
            public EffectActivated(long seq, int turn, Side side, long passcode, long description,
                                   Integer chainIndex) {
                this(seq, turn, side, passcode, description, chainIndex, null);
            }

            public EffectActivated(long seq, int turn, Side side, long passcode, long description) {
                this(seq, turn, side, passcode, description, null, null);
            }
        }

        /**
         * 连锁第 {@code chainIndex} 环开始处理（MSG_CHAIN_SOLVING）。
         *
         * <p>记录里它就是"谁的效果处理了"的分界：这一行之后到下一环之间发生的卡移动/结果，
         * 都属于这一环（MDPRO3 的"N 效果处理"）。passcode 取不到时给 0。
         */
        record ChainSolving(long seq, int turn, int chainIndex, long passcode) implements Entry {
            @Override
            public Side side() {
                return Side.SYSTEM;
            }
        }

        /**
         * 一张卡被公开（检索/确认时亮给对手看的那张）。
         *
         * <p>协议上"进手牌"的消息卡号被抹零，卡号只在这条亮牌消息里，
         * 所以它必须单独存在——渲染层再把它并进那条"加入手牌"。
         */
        record CardRevealed(long seq, int turn, Side side, long passcode) implements Entry {
        }

        /**
         * 连锁第 {@code chainIndex} 环被无效（MSG_CHAIN_NEGATED / MSG_CHAIN_DISABLED）。
         *
         * <p>"效果无效"是记录里的关键事实：它之后的效果结算没有发生——模型据此知道
         * 对方的检索/破坏没有落地，也据此复盘"狼/泡影骗灰流丽"这类交互。
         */
        record ChainNegated(long seq, int turn, int chainIndex, long passcode) implements Entry {
            @Override
            public Side side() {
                return Side.SYSTEM;
            }
        }

        /** 整条连锁处理完毕（MSG_CHAIN_END）：记录里对应一行"处理结束"。 */
        record ChainEnded(long seq, int turn, int chainLength) implements Entry {
            @Override
            public Side side() {
                return Side.SYSTEM;
            }
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
