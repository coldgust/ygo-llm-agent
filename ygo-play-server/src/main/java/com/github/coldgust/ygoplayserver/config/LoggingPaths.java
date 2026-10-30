package com.github.coldgust.ygoplayserver.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 日志目录解析：把"日志写到仓库的 logs/"与启动方式解耦。
 *
 * <p>为什么需要：{@code mvn spring-boot:run} 会把工作目录设成模块目录
 * {@code ygo-play-server/}，从 IDE 启动则通常是仓库根。若直接用相对路径 {@code logs}，
 * 同一个项目会分裂出两个日志目录，排查时找不到上一局的日志。
 *
 * <p>解析顺序：
 * <ol>
 *   <li>环境变量 / 系统属性 {@code YGO_LOG_DIR}（显式指定优先）；</li>
 *   <li>从当前目录逐级向上找"仓库根"（含 {@code .git} 目录或含 {@code modules} 的 {@code pom.xml}），
 *       命中后用 {@code <仓库根>/logs}；</li>
 *   <li>都找不到就退化为 {@code <当前目录>/logs}。</li>
 * </ol>
 */
public final class LoggingPaths {

    /** 系统属性名；logback-spring.xml 读这个值决定文件输出位置。 */
    public static final String LOG_DIR_PROPERTY = "ygo.log.dir";

    private static final int MAX_UPWARD = 6;

    private LoggingPaths() {
    }

    /** 解析日志目录并写成系统属性，供 logback 在初始化时读取。 */
    public static Path configure() {
        Path dir = Path.of(System.getProperty(LOG_DIR_PROPERTY, "")).normalize();
        if (System.getProperty(LOG_DIR_PROPERTY) == null || System.getProperty(LOG_DIR_PROPERTY).isBlank()) {
            String env = System.getenv("YGO_LOG_DIR");
            dir = env != null && !env.isBlank() ? Path.of(env).toAbsolutePath().normalize()
                    : repoRoot().map(root -> root.resolve("logs")).orElseGet(
                            () -> Path.of("").toAbsolutePath().resolve("logs"));
        }
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            System.err.println("[logging] 无法创建日志目录 " + dir + "：" + e);
        }
        System.setProperty(LOG_DIR_PROPERTY, dir.toString());
        return dir;
    }

    /** 向上的"仓库根"：含 .git，或根 pom.xml 里声明了 modules。 */
    private static Optional<Path> repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < MAX_UPWARD && dir != null; i++) {
            if (Files.isDirectory(dir.resolve(".git")) || declaresModules(dir.resolve("pom.xml"))) {
                return Optional.of(dir);
            }
            dir = dir.getParent();
        }
        return Optional.empty();
    }

    private static boolean declaresModules(Path pom) {
        if (!Files.isRegularFile(pom)) {
            return false;
        }
        try {
            return Files.readString(pom).contains("<modules>");
        } catch (Exception e) {
            return false;
        }
    }
}
