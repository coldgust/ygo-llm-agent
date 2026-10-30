package com.github.coldgust.ygoprosdk.deck;

import com.github.coldgust.ygodomain.deck.Deck;

import java.util.ArrayList;
import java.util.List;

/**
 * .ydk 卡组文件解析器（纯文本、零依赖）。
 *
 * <pre>
 * #created by ...
 * #main
 * 89631139
 * ...
 * #extra
 * ...
 * !side
 * ...
 * </pre>
 */
public final class YdkParser {
    private YdkParser() {
    }

    public static Deck parse(String text) {
        // YGOPro 系工具导出的 .ydk 常带 UTF-8 BOM，先剥离（否则首行的 # 注释识别失效）
        String normalized = text.startsWith("\uFEFF") ? text.substring(1) : text;
        List<Long> main = new ArrayList<>();
        List<Long> extra = new ArrayList<>();
        List<Long> side = new ArrayList<>();
        List<Long> current = main;
        for (String rawLine : normalized.split("\r?\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#created")) {
                continue;
            }
            if (line.startsWith("#main")) {
                current = main;
                continue;
            }
            if (line.startsWith("#extra")) {
                current = extra;
                continue;
            }
            if (line.startsWith("!side")) {
                current = side;
                continue;
            }
            if (line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            try {
                current.add(Long.parseLong(line));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(".ydk 含非数字行: " + line, e);
            }
        }
        return new Deck(main, extra, side);
    }
}
