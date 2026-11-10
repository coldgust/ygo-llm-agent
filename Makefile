JAVA_HOME := $(shell /usr/libexec/java_home -v 25 2>/dev/null)
MVN := JAVA_HOME=$(JAVA_HOME) ./mvnw -B

.DEFAULT_GOAL := help

help: ## 列出可用命令
	@grep -E '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

up: ## 启动 postgres + neo4j
	docker compose up -d postgres neo4j

up-rag: ## 启动知识栈（postgres + neo4j + lightrag）
	docker compose up -d postgres neo4j lightrag

up-all: ## 启动全部基础设施（postgres + neo4j + lightrag）
	docker compose up -d postgres neo4j lightrag

down: ## 停止容器
	docker compose down

ps: ## 查看容器状态
	docker compose ps

build: ## 编译打包（跳过测试）
	$(MVN) -DskipTests package

test: ## 单元测试
	$(MVN) test

verify: ## 编译 + 测试 + 打包
	$(MVN) verify

play-server: ## 启动对局 HTTP 服务（默认 8081；需先 build/install）
	$(MVN) -f ygo-play-server/pom.xml spring-boot:run

play-p0: ## P0 实机对局（YDK 必填；HOST/PORT/NAME/MOVE 可覆盖）
	@test -n "$(YDK)" || { echo '用法：make play-p0 YDK=/path/to/deck.ydk [HOST=… PORT=… NAME=… MOVE=first|second]'; exit 1; }
	JAVA_HOME=$(JAVA_HOME) ./ygo-play/run.sh "$(or $(HOST),127.0.0.1)" "$(or $(PORT),7911)" "$(or $(NAME),ygo-p0-runner)" "$(YDK)" "$(or $(MOVE),second)"

reset: ## 清空所有数据卷（危险）
	docker compose down -v

cards-pg: ## 卡牌入库：data/card_rag/cards.jsonl → PG card 表（决策侧 find_card 查卡）
	./ygo-rag/scripts/ingest-cards-pg.sh
