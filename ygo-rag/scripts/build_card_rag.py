import argparse
import json
import os
import re
import shutil
import sqlite3
import tempfile
from collections import Counter

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))
OUT = os.path.join(ROOT, "data", "card_rag")

RACE = {
    0x1: "战士", 0x2: "魔法师", 0x4: "仙女", 0x8: "恶魔", 0x10: "不死",
    0x20: "机械", 0x40: "水", 0x80: "炎", 0x100: "岩石", 0x200: "鸟兽",
    0x400: "植物", 0x800: "昆虫", 0x1000: "雷", 0x2000: "龙",
    0x4000: "兽战士", 0x8000: "兽", 0x10000: "恐龙", 0x20000: "鱼",
    0x40000: "海龙", 0x80000: "爬虫", 0x100000: "念动力", 0x200000: "幻神兽",
    0x400000: "创造神", 0x800000: "幻龙", 0x1000000: "电子界", 0x2000000: "幻想魔",
}

ATTRIBUTE = {
    0x1: "地", 0x2: "水", 0x4: "炎", 0x8: "风",
    0x10: "光", 0x20: "暗", 0x40: "神",
}

T_MONSTER, T_SPELL, T_TRAP = 0x1, 0x2, 0x4
T_NORMAL, T_EFFECT, T_FUSION, T_RITUAL = 0x10, 0x20, 0x40, 0x80
T_SPIRIT, T_UNION, T_DUAL, T_TUNER, T_SYNCHRO, T_TOKEN = 0x200, 0x400, 0x800, 0x1000, 0x2000, 0x4000
T_QUICKPLAY, T_CONTINUOUS, T_EQUIP, T_FIELD, T_COUNTER = 0x10000, 0x20000, 0x40000, 0x80000, 0x100000
T_FLIP, T_TOON, T_XYZ, T_PENDULUM = 0x200000, 0x400000, 0x800000, 0x1000000
T_NOSUMMON, T_LINK = 0x2000000, 0x4000000

LINK_ARROWS = {0x1: "左下", 0x2: "下", 0x4: "右下", 0x8: "左",
               0x20: "右", 0x40: "左上", 0x80: "上", 0x100: "右上"}

SCALE_HEADER = re.compile(r"←\s*(\d+)\s*【灵摆】\s*(\d+)→")


def parse_setnames(path):
    out = {}
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            if not line.startswith("!setname "):
                continue
            body = line[len("!setname "):].strip().split("\t")[0]
            parts = body.split(None, 1)
            if len(parts) != 2:
                continue
            try:
                value = int(parts[0], 16)
            except ValueError:
                continue
            out.setdefault(value, parts[1].strip())
    return out


def decode_type_names(t):
    if t & T_MONSTER or t & T_TOKEN:
        kinds = []
        for bit, name in ((T_FUSION, "融合"), (T_RITUAL, "仪式"), (T_SYNCHRO, "同调"),
                          (T_XYZ, "超量"), (T_LINK, "连接"), (T_TOKEN, "衍生物")):
            if t & bit:
                kinds.append(name)
        if t & T_NORMAL:
            kinds.append("通常")
        elif t & T_EFFECT:
            kinds.append("效果")
        kinds.append("怪兽")
        marks = [n for bit, n in ((T_PENDULUM, "灵摆"), (T_SPIRIT, "灵魂"), (T_UNION, "同盟"),
                                  (T_DUAL, "二重"), (T_TUNER, "调整"), (T_TOON, "卡通"),
                                  (T_FLIP, "反转")) if t & bit]
        return "／".join(marks + kinds)
    if t & T_SPELL:
        for bit, name in ((T_QUICKPLAY, "速攻魔法"), (T_CONTINUOUS, "永续魔法"),
                          (T_EQUIP, "装备魔法"), (T_FIELD, "场地魔法"), (T_RITUAL, "仪式魔法")):
            if t & bit:
                return name
        return "通常魔法"
    if t & T_TRAP:
        if t & T_COUNTER:
            return "反击陷阱"
        if t & T_CONTINUOUS:
            return "永续陷阱"
        return "通常陷阱"
    return "未知"


def decode_setcode(sc, setnames):
    out = []
    for i in range(4):
        short = (sc >> (16 * i)) & 0xFFFF
        if not short:
            continue
        name = setnames.get(short)
        if name is None and short >> 8:
            name = setnames.get(short & 0xFF)
        out.append({"value": short, "name": name or f"0x{short:04x}"})
    return out


def decode_arrows(defv):
    return [name for bit, name in LINK_ARROWS.items() if defv & bit]


def split_desc(desc):
    text = desc.replace("\r\n", "\n").strip()
    scales, header = None, None
    m = SCALE_HEADER.search(text)
    if m:
        header = m.group(0)
        scales = (int(m.group(1)), int(m.group(2)))
        text = (text[:m.start()] + text[m.end():]).lstrip("\n")
    pend, monster = None, None
    if "【怪兽效果】" in text:
        pend, monster = [p.strip() for p in text.split("【怪兽效果】", 1)]
        pend = pend.strip() or None
    else:
        monster = text.strip() or None
    return scales, header, pend, monster


def fmt_stat(v):
    return "？" if v == -2 else ("—" if v == -1 else str(v))


def main():
    parser = argparse.ArgumentParser(description="YGOPro 系客户端 cards.cdb → 结构化卡面数据")
    parser.add_argument("--mdpro3", default=os.environ.get("MDPRO3_DIR"),
                        help="MDPro3 数据目录（内含 Data/locales/*/cards.cdb；或用环境变量 MDPRO3_DIR）")
    parser.add_argument("--ygopro", default=os.environ.get("YGOPRO_DIR"),
                        help="YGOPro 数据目录（内含 strings.conf；或用环境变量 YGOPRO_DIR）")
    args = parser.parse_args()
    if not args.mdpro3 or not args.ygopro:
        parser.error("需要 --mdpro3/--ygopro（或环境变量 MDPRO3_DIR/YGOPRO_DIR）指定客户端数据目录")
    db_zh = os.path.join(args.mdpro3, "Data", "locales", "zh-CN", "cards.cdb")
    db_en = os.path.join(args.mdpro3, "Data", "locales", "en-US", "cards.cdb")
    strings_conf = os.path.join(args.ygopro, "strings.conf")

    setnames = parse_setnames(strings_conf)
    setnames.update(parse_setnames(os.path.join(args.mdpro3, "Data", "locales", "zh-CN", "strings.conf")))

    tmp = tempfile.mkdtemp(prefix="card_rag_")
    try:
        zh = shutil.copy(db_zh, tmp)
        con = sqlite3.connect(f"file:{zh}?mode=ro", uri=True)
        rows = con.execute(
            "SELECT d.id, d.ot, d.alias, d.setcode, d.type, d.atk, d.def, d.level,"
            "       d.race, d.attribute, d.category, t.name, t.desc"
            " FROM datas d JOIN texts t ON t.id = d.id ORDER BY d.id"
        ).fetchall()
        con.close()
        en = sqlite3.connect(f"file:{shutil.copy(db_en, tmp)}?mode=ro", uri=True)
        en_names = dict(en.execute("SELECT id, name FROM texts").fetchall())
        en.close()
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    cards_dir = os.path.join(OUT, "cards")
    os.makedirs(cards_dir, exist_ok=True)

    stats = Counter()
    records = []
    for (cid, ot, alias, setcode, ctype, atk, dvf, level, race, attr, category,
         name, desc) in rows:
        name = (name or "").replace("\r\n", "\n").strip()
        desc = (desc or "").replace("\r\n", "\n").strip()
        en_name = (en_names.get(cid) or "").strip()
        types = decode_type_names(ctype)
        races = [RACE[b] for b in sorted(RACE) if race & b]
        attrs = [ATTRIBUTE[b] for b in sorted(ATTRIBUTE) if attr & b]
        sets = decode_setcode(setcode, setnames)
        scales, scale_header, pend_text, monster_text = (None, None, None, None)
        if ctype & T_PENDULUM:
            scales, scale_header, pend_text, monster_text = split_desc(desc)
            if scales is None and level >= 0x10000:
                scales = ((level >> 16) & 0xFF, (level >> 24) & 0xFF)
        elif "【怪兽效果】" in desc:
            _, _, pend_text, monster_text = split_desc(desc)
        else:
            monster_text = desc or None

        alias_name = None
        if alias:
            alias_name = en_names.get(alias)
        rec = {
            "id": cid, "name": name, "name_en": en_name, "alias": alias or None,
            "ot": ot, "ot_ocg": bool(ot & 0x1), "ot_tcg": bool(ot & 0x2),
            "setcode_raw": setcode, "setcodes": sets,
            "type_raw": ctype, "card_type": types,
            "race_raw": race, "race": races,
            "attribute_raw": attr, "attribute": attrs,
            "atk": atk if atk >= 0 else None, "def": None, "level": None,
            "link_rating": None, "link_arrows": None,
            "pendulum_scale": list(scales) if scales else None,
            "pendulum_effect": pend_text, "monster_effect": monster_text,
            "desc_raw": desc, "category_raw": category,
        }

        lines = [f"# {name}（{en_name}）" if en_name else f"# {name}", ""]
        lines.append(f"- **卡片ID**：{cid}")
        lines.append(f"- **卡片种类**：{types}")
        if ctype & T_MONSTER:
            rec_level = level & 0xFF
            if ctype & T_LINK:
                rating = level & 0xFF
                arrows = decode_arrows(dvf)
                rec["link_rating"], rec["link_arrows"] = rating, arrows
                lines.append(f"- **连接标记**：Link-{rating}（箭头：{'、'.join(arrows)}）")
                lines.append(f"- **攻击力**：{fmt_stat(atk)}")
            else:
                rec["atk"], rec["def"], rec["level"] = (
                    atk if atk >= 0 else None, dvf if dvf >= 0 else None, rec_level)
                if ctype & T_XYZ:
                    lines.append(f"- **阶级**：{rec_level}")
                else:
                    lines.append(f"- **等级**：{rec_level}")
                if ctype & T_PENDULUM and scales:
                    lines.append(f"- **灵摆刻度**：{scales[0]}/{scales[1]}")
                lines.append(f"- **种族**：{'、'.join(races) or '—'}／**属性**：{'、'.join(attrs) or '—'}")
                lines.append(f"- **攻击力**：{fmt_stat(atk)}　**守备力**：{fmt_stat(dvf)}")
            if ctype & T_NOSUMMON:
                lines.append("- **召唤限制**：不能通常召唤（特殊召唤专属）")
        if sets:
            lines.append(f"- **系列**：{'、'.join(s['name'] for s in sets)}")
        ot_tags = "、".join(t for f_, t in ((0x1, "OCG"), (0x2, "TCG")) if ot & f_) or "未收录"
        lines.append(f"- **收录**：{ot_tags}")
        if alias:
            lines.append(f"- **备注**：关联ID {alias}（旧ID/异画版本）")
        lines.append("")
        lines.append("## 卡片文本")
        lines.append("")
        if ctype & T_PENDULUM and pend_text is not None:
            if scales:
                lines.append(f"### 灵摆刻度：{scales[0]}/{scales[1]}")
                lines.append("")
            lines.append("### 灵摆效果")
            lines.append("")
            lines.append(pend_text or "（无）")
            lines.append("")
            lines.append("### 怪兽效果")
            lines.append("")
            lines.append(monster_text or "（无）")
        else:
            lines.append(monster_text or "（无）")
        lines.append("")

        records.append(rec)
        stats["monster" if ctype & (T_MONSTER | T_TOKEN) else "spell" if ctype & T_SPELL else
              "trap" if ctype & T_TRAP else "other"] += 1
        with open(os.path.join(cards_dir, f"{cid}.md"), "w", encoding="utf-8") as f:
            f.write("\n".join(lines))

    name_map = {r["id"]: r["name"] for r in records}
    for r in records:
        if r["alias"]:
            r["alias_name"] = name_map.get(r["alias"])
            r["is_alt_art"] = (r["alias_name"] == r["name"])

    with open(os.path.join(OUT, "cards.jsonl"), "w", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    print(f"cards: {len(records)}  (monster={stats['monster']} spell={stats['spell']} trap={stats['trap']} other={stats['other']})")
    print(f"alt-art dup: {sum(1 for r in records if r.get('is_alt_art'))}")


if __name__ == "__main__":
    main()
