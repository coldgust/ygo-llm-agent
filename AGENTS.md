# AGENTS.md

Java 25 / Maven 多模块的 YGOPro 实机 LLM 对局项目。架构与功能详见 [README.md](README.md)，本文只讲怎么干活。

## 命令

```bash
make build       # 编译打包（跳过测试）
make test        # 单元测试
make verify      # 编译 + 测试 + 打包（CI 等价命令：./mvnw -B -ntp verify），提交前至少跑这个
make play-server # 启动对局服务（:8081）
cd web && pnpm build   # 前端
```

需要 JDK 25（Makefile 自动解析 JAVA_HOME）。CI 只跑 `mvn verify` + `pnpm build`。