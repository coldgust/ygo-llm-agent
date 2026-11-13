package com.github.coldgust.ygoagent.support;

import java.util.List;

/**
 * 连续同文行折叠：与末行相同的新行不再追加，而是把末行改成"行 ×N"。
 * 用于动作列表与决斗记录（对手一次抽 3 张是 3 条同文事件，逐条只会刷屏）；
 * 折叠不设上限，总量裁剪归调用方。
 */
public final class FoldedLines {

    private FoldedLines() {
    }

    public static void add(List<String> lines, String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        if (!lines.isEmpty()) {
            String last = lines.getLast();
            int mark = last.lastIndexOf(" ×");
            String base = mark > 0 ? last.substring(0, mark) : last;
            if (base.equals(line)) {
                int times = mark > 0 ? Integer.parseInt(last.substring(mark + 2)) : 1;
                lines.set(lines.size() - 1, base + " ×" + (times + 1));
                return;
            }
        }
        lines.add(line);
    }
}
