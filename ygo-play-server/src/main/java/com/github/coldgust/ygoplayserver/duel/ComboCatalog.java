package com.github.coldgust.ygoplayserver.duel;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.TreeSet;

/**
 * combo 主题目录：给前端"卡组主题"下拉提供可选值。
 *
 * <p>唯一数据源：PG {@code combo_record} 的 DISTINCT archetype（JSON 种子/编译管线已删除）。
 * 库不可达/表还没建时返回空列表——下拉没有可选项，但不妨碍用户手动输入新主题。
 */
public final class ComboCatalog {

    /** @param jdbc 可空：PG 不可用时没有可选主题 */
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
