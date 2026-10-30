package com.github.coldgust.ygodomain.duel;

import com.github.coldgust.ygodomain.card.CardIdentity;
import com.github.coldgust.ygodomain.zone.ZoneRef;

import java.util.List;
import java.util.ArrayList;

/**
 * 连锁中的一个节点（官方术语：Chain；倒序结算）。
 *
 * @param index       链序号（1 起，1 最先发动、最后结算）
 * @param identity    发动卡身份
 * @param effectDescription  效果描述索引（协议 description 字段）
 * @param source      发动卡所在位置
 * @param controller  发动者
 * @param targets     这一环的对象（协议 BECOME_TARGET）。<b>必须给</b>：卡面只写"以…为对象"，
 *                    不写具体是哪张；不给对象模型只能猜（实机把"除外我方后场"猜成了"除外我方燎里"）。
 * @param negated     这一环是否已被无效（协议 CHAIN_NEGATED / CHAIN_DISABLED）。
 *                    <b>被无效的环节仍然留在链上</b>：它是"这里发生过一次无效"的证据。
 *                    删掉它会让模型看到一条"少了一环"的链（实机：指名者无效灰流丽后，
 *                    链上少了一环，模型据此断言"幕帘已被灰流丽无效"，其实幕帘照常结算了）。
 */
public record ChainNode(
        int index,
        CardIdentity identity,
        long effectDescription,
        ZoneRef source,
        PlayerSide controller,
        boolean negated,
        List<ZoneRef> targets
) {

    public ChainNode {
        targets = targets == null ? List.of() : List.copyOf(targets);
    }

    /** 未被无效、也没有对象信息的常规构造（协议 CHAINING 与全量同步都走这个）。 */
    public ChainNode(int index, CardIdentity identity, long effectDescription,
                     ZoneRef source, PlayerSide controller) {
        this(index, identity, effectDescription, source, controller, false, List.of());
    }

    /** 带"是否被无效"的构造（无对象信息）。 */
    public ChainNode(int index, CardIdentity identity, long effectDescription,
                     ZoneRef source, PlayerSide controller, boolean negated) {
        this(index, identity, effectDescription, source, controller, negated, List.of());
    }

    /** 追加对象（协议 BECOME_TARGET 逐个到达）。 */
    public ChainNode withTargets(List<ZoneRef> more) {
        if (more == null || more.isEmpty()) {
            return this;
        }
        List<ZoneRef> merged = new ArrayList<>(targets);
        merged.addAll(more);
        return new ChainNode(index, identity, effectDescription, source, controller, negated, merged);
    }

    /** 标记为"已被无效"，其余字段不变。 */
    public ChainNode asNegated() {
        return negated ? this
                : new ChainNode(index, identity, effectDescription, source, controller, true, targets);
    }
}
