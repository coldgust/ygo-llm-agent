-- combo 实战录入（Web 功能 2）：用户先手打完一套，AI 回合自动认输，
-- 该回合的逐步操作（自然语言描述）+ LLM 摘要存这里。
-- 注意：服务端也有幂等建表（ComboRecordService#ensureTable），
-- 这个文件只服务全新 compose 卷的首次初始化。
CREATE TABLE IF NOT EXISTS combo_record (
    id             BIGSERIAL PRIMARY KEY,
    duel_id        TEXT        NOT NULL,                   -- 来源对局（/tmp/ygo-duel-log/server-<id>.jsonl 的 id）
    archetype      TEXT        NOT NULL,                   -- 卡组主题（空主题归 UNFILED）
    step_count     INT         NOT NULL,
    steps          JSONB       NOT NULL,                   -- [{seq,turn,phase,kind,desc,passcode,fromZone,toZone,position,player}]
    summary        TEXT,                                   -- LLM 生成的摘要（失败/未配置时为 NULL）
    key_cards      TEXT,                                   -- 关键卡牌（LLM 给出，逗号分隔卡名；find_combos 按它匹配）
    summary_source TEXT        NOT NULL DEFAULT 'NONE',    -- LLM / NONE
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_combo_record_archetype ON combo_record (archetype);
