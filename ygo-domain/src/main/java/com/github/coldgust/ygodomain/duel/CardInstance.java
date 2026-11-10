package com.github.coldgust.ygodomain.duel;

import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.zone.ZoneRef;

import java.util.List;
import java.util.Map;

/**
 * 一张卡在镜像中的当前实例（"这张卡现在在哪、什么状态"）。
 *
 * <p>不可变：战场镜像每次事件后整体重建 {@link DuelState}，
 * 因此实例可以安全地参与相等性比较与去重。
 *
 * @param instanceId    实例标识（镜像内唯一，跨快照不稳定）
 * @param identity      卡身份（已知 / 未知）
 * @param position      表示形式；非场地区域为 null
 * @param counters      指示物（种类 → 数量）
 * @param xyzMaterials  Xyz Material 的实例标识列表（自底向上）
 */
public record CardInstance(
        long instanceId,
        CardIdentity identity,
        ZoneRef zone,
        Position position,
        Map<Long, Integer> counters,
        List<Long> xyzMaterials
) {
    public CardInstance {
        counters = counters == null ? Map.of() : Map.copyOf(counters);
        xyzMaterials = xyzMaterials == null ? List.of() : List.copyOf(xyzMaterials);
    }

    /** identity 可能为 null（镜像解码产生），一律视为未知。 */
    public boolean isUnknown() {
        return identity == null || identity.isUnknown();
    }

    public long passcode() {
        return identity == null ? 0 : identity.passcode();
    }

    public boolean isIn(ZoneRef zone) {
        return zone.equals(this.zone);
    }
}
