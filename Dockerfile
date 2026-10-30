# ygo-graph-agent 对局服务镜像（ygo-play-server + Svelte 前端产物）
#
# 构建上下文 = 仓库根；数据（data/）不进镜像，运行时挂载（见 compose.yaml server 服务）。
#   docker compose build server
#   docker compose up -d

# ---------- 1/3 前端构建 ----------
FROM node:22-alpine AS web
WORKDIR /src/web
# 先只拷 package.json，依赖层可被缓存（仓库无 pnpm-lock，用 npm 安装）
COPY web/package.json ./
RUN npm install --no-audit --no-fund
COPY web/ ./
RUN npm run build

# ---------- 2/3 后端构建 ----------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
# 先拷构建描述，尽量缓存依赖下载
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY ygo-domain/pom.xml ygo-domain/
COPY ygopro-sdk/pom.xml ygopro-sdk/
COPY ygo-agent/pom.xml ygo-agent/
COPY ygo-duel-log/pom.xml ygo-duel-log/
COPY ygo-play/pom.xml ygo-play/
COPY ygo-play-server/pom.xml ygo-play-server/
RUN ./mvnw -B -q dependency:go-offline || true
COPY ygo-domain/ ygo-domain/
COPY ygopro-sdk/ ygopro-sdk/
COPY ygo-agent/ ygo-agent/
COPY ygo-duel-log/ ygo-duel-log/
COPY ygo-play/ ygo-play/
COPY ygo-play-server/ ygo-play-server/
RUN ./mvnw -B -q -DskipTests package

# ---------- 3/3 运行 ----------
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/ygo-play-server/target/ygo-play-server-*.jar app.jar
COPY --from=web /src/web/dist web/dist
# 数据与日志走挂载：data/（combo 库 + 卡面兜底表）、logs/、trace 与对局日志
ENV YGO_LOG_DIR=/app/logs \
    PLAY_SERVER_PORT=8081
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
