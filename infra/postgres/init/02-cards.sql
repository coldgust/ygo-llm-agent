-- 卡牌事实表：决策侧"查卡"走这里（替代启动时把 15019 行读进内存）。
-- 与 data/card_rag/cards.jsonl 字段一一对应；kind 由 card_type 推导，不落库。
CREATE TABLE IF NOT EXISTS card (
    passcode        BIGINT PRIMARY KEY,
    name            TEXT NOT NULL,
    name_en         TEXT,
    card_type       TEXT,
    race            TEXT,
    attribute       TEXT,
    atk             INTEGER,
    def             INTEGER,
    level           INTEGER,
    monster_effect  TEXT,
    pendulum_effect TEXT
);

CREATE INDEX IF NOT EXISTS idx_card_name_lower ON card (lower(name));
CREATE INDEX IF NOT EXISTS idx_card_name_en_lower ON card (lower(name_en));

-- 中文卡名包含匹配（pg_trgm 不可用时跳过，不阻塞建表）
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS pg_trgm;
    CREATE INDEX IF NOT EXISTS idx_card_name_trgm ON card USING GIN (name gin_trgm_ops);
    CREATE INDEX IF NOT EXISTS idx_card_name_en_trgm ON card USING GIN (name_en gin_trgm_ops);
EXCEPTION WHEN others THEN
    RAISE NOTICE 'pg_trgm 不可用，跳过 GIN 索引';
END $$;
