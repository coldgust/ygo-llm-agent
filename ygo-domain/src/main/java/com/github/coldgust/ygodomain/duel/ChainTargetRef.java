package com.github.coldgust.ygodomain.duel;

import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.zone.ZoneRef;

/**
 * 连锁的对象（协议 MSG_BECOME_TARGET 的一个目标）。
 *
 * <p>身份必须在协议包解析那一刻解出并随目标携带：列表区域（墓地/除外/额外卡组）的
 * ZoneRef 不携带引擎下标（区域本身不可区分同区域的卡），事后按区域回查要么查不到、
 * 要么在多张同区域卡里取错——实战事故：蒂迈欧之眼光的对象是墓地毁灭之黑魔术师（下标 8），
 * 被往返抹成下标 0 解成了最早进墓的欢聚友伴·茸茸长尾山雀。
 *
 * @param zone     对象所在区域（仅供显示；不可用于身份寻址）
 * @param identity 对象身份——按协议下标当场解出，之后卡离场也不会变；解不出为 UNKNOWN
 */
public record ChainTargetRef(ZoneRef zone, CardIdentity identity) {

    /** 对象卡号（身份未知时 0）。 */
    public long passcode() {
        return identity == null || identity.isUnknown() ? 0 : identity.passcode();
    }
}
