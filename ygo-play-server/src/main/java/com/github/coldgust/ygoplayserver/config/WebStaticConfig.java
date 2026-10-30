package com.github.coldgust.ygoplayserver.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 托管 Svelte 前端构建产物（web/dist）。/api/** 由控制器优先处理，其余走静态资源。
 */
@Configuration
public class WebStaticConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebStaticConfig.class);

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        distLocation().ifPresent(dist -> {
            log.info("托管前端静态资源: {}", dist.toAbsolutePath());
            registry.addResourceHandler("/**")
                    .addResourceLocations("file:" + dist.toAbsolutePath() + "/");
        });
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 欢迎页：/ → index.html（自定义 resource handler 下 Boot 不自动识别欢迎页）
        distLocation().ifPresent(dist -> registry.addViewController("/").setViewName("forward:/index.html"));
    }

    /** web/dist 的候选位置：工作目录与上级（spring-boot:run 的工作目录在 ygo-play-server/）。 */
    static Optional<Path> distLocation() {
        for (Path candidate : List.of(Path.of("web", "dist"), Path.of("..", "web", "dist"))) {
            if (Files.isRegularFile(candidate.resolve("index.html"))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
