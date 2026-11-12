package com.github.coldgust.ygoagent.pipeline.plan;

import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.zone.ZoneKind;
import com.github.coldgust.ygodomain.zone.ZoneRef;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 区域格子的"人机共同语"：<b>一个稳定编码</b>，给模型看、也让模型写回来。
 * <p>编号从 <b>1</b> 起（对应玩家看到的"第 3 格"），与协议 sequence（0 起）差一。
 */
public final class ZoneCodes {

    /** 主怪兽区 5 格；额外怪兽区 2 格；魔陷区 5 格；灵摆区 2 格。 */
    private static final Pattern DIGITS = Pattern.compile("(\\d+)");
    private static final Pattern SHORT_MAIN = Pattern.compile("^M(\\d+)$");

    private ZoneCodes() {
    }


    /** 区域 → 编码（{@code MMZ-3}）。非场上区域给 {@code HAND/GY/BAN/DECK/EXD}。 */
    public static String code(ZoneRef zone) {
        if (zone == null) {
            return "?";
        }
        return switch (zone.kind()) {
            case MAIN_MONSTER_ZONE -> "MMZ-" + (zone.sequence() + 1);
            case EXTRA_MONSTER_ZONE -> "EMZ-" + (zone.sequence() + 1);
            case SPELL_TRAP_ZONE -> "STZ-" + (zone.sequence() + 1);
            case PENDULUM_ZONE -> "PZ-" + (zone.sequence() + 1);
            case FIELD_ZONE -> "FZ";
            case HAND -> "HAND";
            case GRAVEYARD -> "GY";
            case BANISHED -> "BAN";
            case DECK -> "DECK";
            case EXTRA_DECK -> "EXD";
        };
    }

    public static String label(ZoneRef zone) {
        if (zone == null) {
            return "?";
        }
        String owner = zone.owner() == PlayerSide.SELF ? "我方" : "对方";
        return switch (zone.kind()) {
            case MAIN_MONSTER_ZONE -> owner + "主怪兽区#" + (zone.sequence() + 1);
            case EXTRA_MONSTER_ZONE -> owner + "额外怪兽区#" + (zone.sequence() + 1);
            case SPELL_TRAP_ZONE -> owner + "魔陷区#" + (zone.sequence() + 1);
            case PENDULUM_ZONE -> owner + "灵摆区#" + (zone.sequence() + 1);
            case FIELD_ZONE -> owner + "场地魔法区";
            case HAND -> owner + "手牌";
            case GRAVEYARD -> owner + "墓地";
            case BANISHED -> owner + "除外区";
            case DECK -> owner + "卡组";
            case EXTRA_DECK -> owner + "额外卡组";
        };
    }

    public static String describe(ZoneRef zone) {
        return code(zone) + "（" + label(zone) + "）";
    }


    public static Optional<ZoneRef> parse(String raw, PlayerSide fallbackOwner) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String text = raw.trim();
        PlayerSide owner = ownerOf(text, fallbackOwner == null ? PlayerSide.SELF : fallbackOwner);

        String s = text.toUpperCase(Locale.ROOT)
                .replaceAll("[\\s#＃:：,，、_\\-—/|]", "")
                .replace("格", "")
                .replace("位置", "");

        ZoneKind kind = kindOf(s);
        if (kind == null) {
            return Optional.empty();
        }
        int seq = sequenceOf(s, kind);
        return Optional.of(new ZoneRef(kind, owner, seq));
    }

    private static PlayerSide ownerOf(String text, PlayerSide fallback) {
        String t = text.toUpperCase(Locale.ROOT);
        if (t.contains("对方") || t.contains("对手") || t.contains("OPP")) {
            return PlayerSide.OPPONENT;
        }
        if (t.contains("我方") || t.contains("自己") || t.contains("SELF")) {
            return PlayerSide.SELF;
        }
        return fallback;
    }

    private static ZoneKind kindOf(String s) {
        // 先匹配额外怪兽区：字符串里也含"怪兽区"，顺序错了会被主怪兽区抢走
        if (s.startsWith("EMZ") || s.startsWith("EXMZ") || s.contains("额外怪兽区") || s.contains("额外怪区")) {
            return ZoneKind.EXTRA_MONSTER_ZONE;
        }
        if (s.startsWith("MMZ") || s.startsWith("MZ") || SHORT_MAIN.matcher(s).matches()
                || s.contains("主怪兽区") || s.contains("主怪区") || s.contains("怪兽区")) {
            return ZoneKind.MAIN_MONSTER_ZONE;
        }
        if (s.startsWith("STZ") || s.startsWith("SZ") || s.contains("魔陷区")
                || s.contains("魔法陷阱区") || s.contains("魔法与陷阱区")) {
            return ZoneKind.SPELL_TRAP_ZONE;
        }
        if (s.startsWith("PZ") || s.contains("灵摆区")) {
            return ZoneKind.PENDULUM_ZONE;
        }
        if (s.startsWith("FZ") || s.contains("场地魔法区") || s.contains("场地区")
                || s.contains("场地魔法卡区域")) {
            return ZoneKind.FIELD_ZONE;
        }
        return null;
    }

    private static int sequenceOf(String s, ZoneKind kind) {
        int max = switch (kind) {
            case MAIN_MONSTER_ZONE, SPELL_TRAP_ZONE -> 4;
            case EXTRA_MONSTER_ZONE, PENDULUM_ZONE -> 1;
            default -> 0;
        };
        Matcher m = DIGITS.matcher(s);
        int last = -1;
        while (m.find()) {
            last = Integer.parseInt(m.group(1));
        }
        if (last < 0) {
            return 0;
        }
        // 1 起算：MMZ-3 → 2；写成 0 时按"最左"处理，别让 0 变成 -1
        int seq = last <= 0 ? 0 : last - 1;
        return Math.max(0, Math.min(max, seq));
    }

    /** 是否为"场上格子"（可放置/可指向的区域）；手牌/墓地等不属于位置决策的候选。 */
    public static boolean isBoardSlot(ZoneRef zone) {
        return zone != null && switch (zone.kind()) {
            case MAIN_MONSTER_ZONE, EXTRA_MONSTER_ZONE, SPELL_TRAP_ZONE, PENDULUM_ZONE, FIELD_ZONE -> true;
            default -> false;
        };
    }
}
