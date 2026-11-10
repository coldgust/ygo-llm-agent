package com.github.coldgust.ygoplayserver.duel;

import java.util.List;

public interface DuelService {

    DuelView start(DuelRequest request);

    DuelView get(String id);

    List<DuelView> list();

    DuelView stop(String id);

    DuelView archiveDecision(String id, boolean store);

    String traceId(String id);
}
