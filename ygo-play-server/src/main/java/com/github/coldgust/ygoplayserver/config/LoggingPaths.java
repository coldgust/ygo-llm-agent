package com.github.coldgust.ygoplayserver.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class LoggingPaths {

    /** 系统属性名；logback-spring.xml 读这个值决定文件输出位置。 */
    public static final String LOG_DIR_PROPERTY = "ygo.log.dir";

    private static final int MAX_UPWARD = 6;

    private LoggingPaths() {
    }

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
