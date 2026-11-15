package com.github.coldgust.ygoplayserver.duel;

import com.github.coldgust.ygoplayserver.duel.combo.ComboRecordRepository;

import java.util.List;
import java.util.TreeSet;

/**
 * combo 主题目录(前端「卡组主题」下拉的数据源)。
 *
 * <p>保持 static util:两个调用点之一是同样临时的 {@code PgRecordedComboSource},
 * 为它单独 bean 化收益不抵装配成本。库不可达时静默返回空列表(下拉里就没有可选主题)。
 */
public final class ComboCatalog {

    public static List<String> archetypes(ComboRecordRepository repository) {
        TreeSet<String> tags = new TreeSet<>();
        if (repository != null) {
            try {
                repository.findDistinctArchetypes().stream()
                        .filter(tag -> tag != null && !tag.isBlank())
                        .forEach(tags::add);
            } catch (RuntimeException ignored) {
                // 表还没建/库不可达:下拉里就没有可选主题
            }
        }
        return List.copyOf(tags);
    }

    private ComboCatalog() {
    }
}