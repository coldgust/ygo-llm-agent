package com.github.coldgust.ygoplayserver.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 前后端分离：允许 Svelte 开发服务器（Vite :5173）跨域访问 /api/**。
 * 生产部署可直接由后端托管 web/dist（WebStaticConfig），此时无需 CORS。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://localhost:5173", "http://127.0.0.1:5173")
                .allowedMethods("GET", "POST", "DELETE")
                .allowedHeaders("*")
                .allowCredentials(false);
    }
}
