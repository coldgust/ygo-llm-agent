# AGENTS.md

给 AI 编码代理的约定。架构与用法见 [README.md](README.md)。

## 构建与提交

- Java 25 / Maven 多模块（Spring Boot 4.1.2），前端 `web/` 是 Svelte + pnpm。
- 依赖单向：`ygo-play` / `ygo-play-server` → `ygo-agent-core` → `ygo-domain` / `ygopro-sdk` / `ygo-duel-log`；`ygo-domain` 不依赖框架，`ygo-agent-core` 不碰 web/DB，`ygopro-sdk` 不做决策。
- Java 走 `make`（已解析 JDK 25）：`make verify` 与 CI 同款；单模块迭代用 `./mvnw -pl <module> -am test`（须 JDK 25，`-am` 防兄弟模块旧快照）。
- 前端只有 build 校验（无 lint / test）：`cd web && pnpm install --frozen-lockfile && pnpm build`。
- 提交前：`make verify` 全绿，动过 `web/` 跑前端 build，diff 不含 `target/`、`web/dist/`、`logs/`、`data/` 生成物。

## 注释：默认不写

注释是「代码里读不出来的信息」，值得写的只有三类，一两句说完：

1. 游戏规则 / YGOPro 协议事实：OCG 裁定、时点、区域与位置编码、效果描述码、协议字节含义（如 `r.u8(); // player`）。
2. 不这么写会出事的硬约束：fail-closed 降级、必须接住 `Throwable`、logback 初始化顺序。
3. 带具体待办的 TODO/FIXME，不写空泛的「以后优化」。

不写：复述代码的 Javadoc、与签名同义的 `@param/@return`、横幅分隔线、死代码、直译行内注释（`x = null; // 置空`）。

两处例外删前先想清楚：`compose.yaml` 注释掉的挂载是 README 引用的示例；`ygo-rag/scripts/ingest-rules.sh` 头部注释被 `-h` 按行号读成帮助文本，删注释要同步改行号。

踩坑、事故复盘、设计权衡等长篇归档进 pitfalls 文档，不进代码；改实现前先翻它。准则：**删掉这句，读者会损失代码之外的信息吗？不会 → 删。**
