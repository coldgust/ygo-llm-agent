package com.github.coldgust.ygoplayserver.duel;

import java.util.List;

public interface DuelService {

    DuelView start(DuelRequest request);

    DuelView get(String id);

    List<DuelView> list();

    DuelView stop(String id);

    /**
     * 用户对「结束后询问」模式的入库决定（store=true 写对局库 / false 跳过）。
     * 仅 ask 模式且已正常结束的局可决定；重复决定幂等返回现状。
     */
    DuelView archiveDecision(String id, boolean store);

    /** SSE 频道 id（trace 文件名去扩展名）。 */
    String traceId(String id);
}
