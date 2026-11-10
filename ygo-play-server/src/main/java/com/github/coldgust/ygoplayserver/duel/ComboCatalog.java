package com.github.coldgust.ygoplayserver.duel;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.TreeSet;

public final class ComboCatalog {

    public static List<String> archetypes(JdbcClient jdbc) {
        TreeSet<String> tags = new TreeSet<>();
        if (jdbc != null) {
            try {
                jdbc.sql("SELECT DISTINCT archetype FROM combo_record")
                        .query(String.class)
                        .list()
                        .stream()
                        .filter(tag -> tag != null && !tag.isBlank())
                        .forEach(tags::add);
            } catch (RuntimeException ignored) {
                // 表还没建/库不可达：下拉里就没有可选主题
            }
        }
        return List.copyOf(tags);
    }

    private ComboCatalog() {
    }
}
