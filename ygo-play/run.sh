#!/usr/bin/env bash
# P0 实机运行：连房间 → 上传卡组 → 决策循环。
#
#   ./ygo-play/run.sh [host] [port] [name] [.ydk] [first|second]
#
# 依赖：JDK 25。可用 MAVEN_REPO_LOCAL 指定本地仓库（沙箱环境常用）。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-$HOME/Library/Java/JavaVirtualMachines/openjdk-25.0.2/Contents/Home}"
MVN="$ROOT_DIR/mvnw -B -q"
REPO_ARG=()
if [[ -n "${MAVEN_REPO_LOCAL:-}" ]]; then
  REPO_ARG=(-Dmaven.repo.local="${MAVEN_REPO_LOCAL}")
fi

# 1) 编译并安装 reactor 模块（跳过 spring-boot 重打包，保证依赖是普通 jar）
JAVA_HOME="$JAVA_HOME" $MVN ${REPO_ARG[@]+"${REPO_ARG[@]}"} -DskipTests -Dspring-boot.repackage.skip=true \
  -pl ygo-play -am install

# 2) 生成运行时 classpath
JAVA_HOME="$JAVA_HOME" $MVN ${REPO_ARG[@]+"${REPO_ARG[@]}"} -pl ygo-play \
  dependency:build-classpath -Dmdep.outputFile=target/classpath.txt

CP="$ROOT_DIR/ygo-play/target/classes:$(cat "$ROOT_DIR/ygo-play/target/classpath.txt")"
# CLI 用 slf4j-simple 输出日志（server 侧用 logback，不能混入）
SIMPLE_JAR="$(find "$HOME/.m2/repository/org/slf4j/slf4j-simple" -name 'slf4j-simple-*.jar' 2>/dev/null | grep -v sources | head -1)"
if [[ -n "$SIMPLE_JAR" ]]; then CP="$SIMPLE_JAR:$CP"; fi
exec "$JAVA_HOME/bin/java" -cp "$CP" com.github.coldgust.ygoplay.DuelRunnerMain "$@"
