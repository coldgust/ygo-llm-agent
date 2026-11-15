package com.github.coldgust.ygoprosdk.core;

import com.github.coldgust.ygodomain.zone.ZoneRef;

import java.util.List;

/**
 * 投影层向记录层透传的协议级事件。
 *
 * <p>为什么要透传：记录层的状态 diff 丢失两类关键信息——
 * <ul>
 *   <li><b>来源区域</b>：卡组/额外卡组里的卡没有实例，diff 只能写"凭空出现"，
 *       记录变成「进入墓地（从 未知区域）」（MDPRO3 会如实画出 EX→墓地 的代价箭头）；</li>
 *   <li><b>移动原因</b>：MSG_MOVE 的 reason 位（代价/破坏/解放/素材…）协议里有、
 *       diff 推不出来——"代价"是 MDPRO3 记录的一级要素，模型要靠它分清
 *       "这张卡进墓是效果结果还是已付的代价"。</li>
 * </ul>
 * 会话在每条协议消息应用后 drain 本队列并通知 {@code DuelListener}。
 */
public final class ProtocolEvents {

    private ProtocolEvents() {
    }

    /**
     * 一次协议层卡移动。
     *
     * @param controller  移动后控制者的绝对座位
     * @param passcode    卡号（抹零时回填实例身份；仍未知为 0）
     * @param from        来源区域（观察者相对视角；卡组/额外卡组的 sequence 无意义）
     * @param to          落点区域
     * @param toPosition  落点表示形式（Position 枚举名；非场地区域为 null）
     * @param reason      MSG_MOVE reason 位的可读标签（如 "COST"、"EFFECT+DESTROY"；0 = null）
     * @param instanceId  移动后卡的镜像实例 id（记录层据此抑制 diff 重复事件）
     */
    public record ProtocolMove(int controller, long passcode, ZoneRef from, ZoneRef to,
                               String toPosition, String reason, long instanceId) {
    }

    /**
     * 连锁对象确定（MSG_BECOME_TARGET）：对象属于最近入链的那一环。
     *
     * @param chainIndex    连锁环号（1 起）；链尚未建立时为 null（对象先到的乱序场景）
     * @param activatorSeat 发动方绝对座位；解不出为 -1
     */
    public record ChainTargeted(Integer chainIndex, int activatorSeat, List<ChainTarget> targets) {
        public ChainTargeted {
            targets = targets == null ? List.of() : List.copyOf(targets);
        }
    }

    /** 一个对象：区域 + 身份（身份解不出为 0）。 */
    public record ChainTarget(ZoneRef zone, long passcode) {
    }
}
