#!/usr/bin/env bash
# 把 data/card_rag/cards.jsonl 导入 PostgreSQL 的 card 表（决策侧查卡走 PG）。
#
# 用法：./ygo-rag/scripts/ingest-cards-pg.sh [cards.jsonl]
# 连接：默认 docker 容器 ygo-postgres / 库 ygo / 用户 ygo；
#       宿主机有 psql 时用 PGHOST/PGPORT/PGUSER/PGPASSWORD，否则 docker exec。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JSONL="${1:-$ROOT/data/card_rag/cards.jsonl}"
CONTAINER="${PG_CONTAINER:-ygo-postgres}"
DB="${PGDATABASE:-ygo}"
USER="${PGUSER:-ygo}"

if [ ! -f "$JSONL" ]; then
  echo "找不到 $JSONL" >&2
  exit 1
fi

run_psql() {
  if command -v psql >/dev/null 2>&1; then
    PGPASSWORD="${PGPASSWORD:-ygo}" psql -h "${PGHOST:-localhost}" -p "${PGPORT:-5432}" \
      -U "$USER" -d "$DB" -v ON_ERROR_STOP=1 "$@"
  else
    docker exec -i "$CONTAINER" psql -U "$USER" -d "$DB" -v ON_ERROR_STOP=1 "$@"
  fi
}

echo "建表：$DB"
run_psql < "$ROOT/infra/postgres/init/02-cards.sql"

CSV="$(mktemp)"
trap 'rm -f "$CSV"' EXIT
jq -r '[.id, .name, (.name_en // ""), (.card_type // ""),
        ((.race // []) | join("/")), ((.attribute // []) | join("/")),
        (.atk // null), (.def // null), (.level // null),
        (.monster_effect // ""), (.pendulum_effect // "")] | @csv' "$JSONL" > "$CSV"

echo "导入 $(wc -l < "$CSV" | tr -d ' ') 行（先清表：passcode 是主键，追加重导会撞键）"
run_psql -c "TRUNCATE card"
cat "$CSV" | run_psql -c "COPY card(passcode,name,name_en,card_type,race,attribute,atk,def,level,monster_effect,pendulum_effect) FROM STDIN CSV"

echo -n "card 表行数："
run_psql -t -c "SELECT count(*) FROM card"
