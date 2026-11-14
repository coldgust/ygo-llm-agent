package com.github.coldgust.ygoplay;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CLI 组装根的配置源读取（dotenv-java 解析）：返回"OS env 优先、.env 兜底"的合并表——
 * 优先级由 dotenv-java 内置保证（host env 覆盖 .env，除非 .ignoreEnvVar()），本项目不再手动处理 OS env。
 * 另有：剥单引号（旧自研解析兼容，dotenv-java 只剥双引号）、跳过 `#` 注释行、剥离行内注释
 * （`KEY=value # 说明` 的注释部分不再混进值里）、坏行忽略；文件不存在静默返回空表。
 * 合并表会混入全部 OS env 变量（不只 .env 声明项）——调用方只按已知键读取，无功能影响。
 * 只有这里（和 server 的 spring.config.import）允许读 .env；core 不读环境变量。
 */
public final class DotEnv {

    public static Map<String, String> load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            Dotenv dotenv = Dotenv.configure()
                    .directory(file.getParent() == null ? "." : file.getParent().toString())
                    .filename(file.getFileName().toString())
                    .ignoreIfMissing()
                    .ignoreIfMalformed()
                    .load();
            Map<String, String> values = new LinkedHashMap<>();
            for (DotenvEntry entry : dotenv.entries()) {
                values.put(entry.getKey(), stripSingleQuotes(entry.getValue()));
            }
            return Map.copyOf(values);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /** 旧自研解析剥单引号（dotenv-java 只剥双引号）——为兼容既有 .env 保留这一层。 */
    private static String stripSingleQuotes(String value) {
        return value != null && value.length() >= 2
                && value.startsWith("'") && value.endsWith("'")
                ? value.substring(1, value.length() - 1)
                : value;
    }

    private DotEnv() {
    }
}
