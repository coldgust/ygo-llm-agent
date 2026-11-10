#!/usr/bin/env bash
# 规则书入库：整本 PDF 上传，**用文件名承载域名前缀**。
# 约定：文件名 = <domain>-<source>.pdf（默认 rule-ocg-zh.pdf）。
# LightRAG 的 /documents/upload 用"上传的文件名"当 file_source，所以文件名就是来源标识；
# 规则书是 rule-*，对局复盘是 duel:*，检索时能按来源分开（RulebookTool / DuelRecallTool 都按前缀过滤）。
# 用法：
#   ./ygo-rag/scripts/ingest-rules.sh                       # 默认 rule-ocg-zh.pdf
#   ./ygo-rag/scripts/ingest-rules.sh --pdf x.pdf --source ocg-zh-v11
#   ./ygo-rag/scripts/ingest-rules.sh --no-wait
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
URL="${LIGHTRAG_URL:-http://localhost:9621}"
PDF="$ROOT/ygo-rag/corpus/rules/rule-ocg-zh.pdf"
SOURCE="ocg-zh"
DOMAIN="rule"
WAIT=1

while [ $# -gt 0 ]; do
  case "$1" in
    --pdf) PDF="$2"; shift 2 ;;
    --source) SOURCE="$2"; shift 2 ;;
    --domain) DOMAIN="$2"; shift 2 ;;
    --no-wait) WAIT=0; shift ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

API_KEY="${LIGHTRAG_API_KEY:-}"
if [ -z "$API_KEY" ] && [ -f "$ROOT/.env" ]; then
  API_KEY="$(grep -E '^LIGHTRAG_API_KEY=' "$ROOT/.env" | cut -d= -f2- | tr -d '[:space:]')"
fi
if [ -z "$API_KEY" ]; then
  echo "缺少 LIGHTRAG_API_KEY（环境变量或 .env）" >&2
  exit 1
fi
AUTH=(-H "X-API-Key: $API_KEY")

if [ ! -f "$PDF" ]; then
  echo "找不到 PDF：$PDF" >&2
  exit 1
fi
if ! curl -fsS -m 5 "${AUTH[@]}" "$URL/health" >/dev/null; then
  echo "LightRAG 不可达：$URL" >&2
  exit 1
fi

TARGET="$DOMAIN-$SOURCE.pdf"
STAGE_DIR="$(mktemp -d)"
cp "$PDF" "$STAGE_DIR/$TARGET"

echo "上传 $(basename "$PDF") → file_source=${TARGET}（$(du -h "$PDF" | cut -f1)）"
RESP="$(curl -sS -m 900 -X POST "$URL/documents/upload" "${AUTH[@]}" \
  -F "file=@$STAGE_DIR/$TARGET" || true)"
rm -rf "$STAGE_DIR"

if ! echo "$RESP" | grep -q '"status"'; then
  echo "上传失败：$(echo "$RESP" | head -c 300)" >&2
  exit 1
fi

if [ "$WAIT" = "0" ]; then
  echo "已提交，抽取在后台进行（--no-wait）"
else
  echo "等待抽取（整本 96 页会被切成几十块，逐块走 LLM，通常 10–30 分钟）…"
  for _ in $(seq 1 180); do
    busy="$(curl -sS -m 10 "${AUTH[@]}" "$URL/documents/pipeline_status" | jq -r '.busy')"
    [ "$busy" != "true" ] && break
    sleep 10
  done
fi

echo "=== 当前文档状态 ==="
curl -sS -m 15 -X POST "$URL/documents/paginated" "${AUTH[@]}" \
  -H 'Content-Type: application/json' -d '{"page":1,"page_size":200}' \
  | jq -c '{counts:.status_counts, sources:[.documents[].file_path]}'

cat <<EOF

验证（应只命中 $DOMAIN- 来源，不应出现 duel:/card:）：
  KEY="$API_KEY"
  curl -s -X POST "$URL/query/data" -H "X-API-Key: \$KEY" -H 'Content-Type: application/json' \\
    -d '{"query":"额外怪兽区能不能放置连接怪兽","mode":"mix","top_k":6,"chunk_top_k":6,"include_chunk_content":true}' \\
    | jq -r '.data.chunks[] | .file_path'
EOF
