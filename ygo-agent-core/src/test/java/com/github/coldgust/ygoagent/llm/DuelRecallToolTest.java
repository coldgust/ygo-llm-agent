package com.github.coldgust.ygoagent.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import tools.jackson.databind.json.JsonMapper;

class DuelRecallToolTest {

    @Test
    void duelDomainFilterOnlyAcceptsDuelSources() {
        assertTrue(DuelRecallTool.isDuelDomain("duel:20261030-001"));
        assertTrue(DuelRecallTool.isDuelDomain("DUEL:abc"));

        assertFalse(DuelRecallTool.isDuelDomain("rulebook_v10_en_p1-p2"));
        assertFalse(DuelRecallTool.isDuelDomain("card:123"));
        assertFalse(DuelRecallTool.isDuelDomain("combo:sky-striker"));
        assertFalse(DuelRecallTool.isDuelDomain(null));
        assertFalse(DuelRecallTool.isDuelDomain(""));
    }

    private static final String SUMMARY = """
            domain: duel
            duel: d1
            myArchetype: SKY_STRIKER
            result: P1 胜（NORMAL）
            ---

            ## 一句话结论
            后手空场过回合，被对手铺场 OTK。

            ## 局面走向
            T1 我方盖双纽闪门结束回合，手牌剩 4 张。
            T2 对手特召、清后场，我方无手坑，LP 归零。

            ## 关键决策
            T1 [照计划] 盖放魔陷 双纽闪门 —— 想留阻抗 → 被羽毛扫清掉。

            ## 转折点
            T2 对手第一次特召时我方没有手坑，此后彻底失控。

            ## 失误与代价
            T1 把唯一阻抗盖在后场 ｜ 代价：被一张羽毛扫清掉 ｜ 本可以：留手牌做后续资源。

            ## 下次遇到类似局面
            空场且只有一张后场阻抗时，先确认对手是否会拆后场，再决定是否盖。

            ## 检索关键词
            闪刀、后手、空场、OTK
            """;

    @Test
    void shortSummaryIsReturnedAsIs() {
        String out = DuelRecallTool.compact("## 一句话结论\n短。", 1_000);
        assertTrue(out.contains("短。"), out);
    }

    @Test
    void longSummaryKeepsConclusionAndLessonsButDropsPlayByPlay() {
        // 素材约 380 字；给 320 的预算：放得下"结论 + 教训"，放不下中间的流水账
        assertTrue(SUMMARY.length() > 320, "用例前提：素材要超过预算");
        String out = DuelRecallTool.compact(SUMMARY, 320);

        assertTrue(out.length() <= 320, "应受预算约束：" + out.length());
        assertTrue(out.contains("domain: duel"), "事实头要保留（供模型判断这局是什么对局）");
        assertTrue(out.contains("## 一句话结论"), out);
        assertTrue(out.contains("## 失误与代价"), out);
        assertTrue(out.contains("## 下次遇到类似局面"), out);
        // 流水账在预算不够时被牺牲，这是有意的
        assertFalse(out.contains("## 局面走向"), out);
    }

    @Test
    void legacyPlainTextFallsBackToTruncation() {
        String legacy = "（本次复盘由模板生成）\n关键决策：\n- T1 [照计划] 结束回合\n".repeat(20);
        String out = DuelRecallTool.compact(legacy, 100);
        assertTrue(out.length() <= 101, "老格式只能截断：" + out.length());
        assertTrue(out.contains("模板生成"), out);
    }

    @Test
    void overlappingChunkBoundariesAreNotDuplicated() throws Exception {
        // 真实切块重叠不总是"干净的后缀"：第二个 chunk 从某条建议的第二个字开始，同一段话出现两遍
        String json = """
                {"data":{"chunks":[
                  {"file_path":"duel:d9","content":"## 下次遇到类似局面\\n- 对手盖卡多、我方有羽毛扫时先扫后场，避免铺完被反制。\\n- LP 低于 3000 时优先盖达磨羯磨。"},
                  {"file_path":"duel:d9","content":"手盖卡多、我方有羽毛扫时先扫后场，避免铺完被反制。\\n- LP 低于 3000 时优先盖达磨羯磨。"}
                ]}}
                """;
        DuelRecallTool tool = new DuelRecallTool(new LightRagSettings("http://localhost:9621", "", 8_000), "mix", 4);
        String out = tool.render(
                JsonMapper.builder().build().readTree(json), 4);

        int first = out.indexOf("优先盖达磨羯磨");
        int second = out.indexOf("优先盖达磨羯磨", first + 1);
        assertTrue(first > 0, out);
        assertTrue(second < 0, "重复段落应被去掉：" + out);
    }

    @Test
    void chunksOfTheSameDuelAreMergedInsteadOfDropped() throws Exception {
        // 一份长复盘会被切成多个 chunk；只留第一条就会丢掉"失误/下次怎么做"
        String json = """
                {"data":{"chunks":[
                  {"file_path":"duel:d1","content":"domain: duel\\n## 一句话结论\\n空场被 OTK。"},
                  {"file_path":"duel:d1","content":"## 失误与代价\\nT3 没转连接怪兽。"},
                  {"file_path":"duel:d1","content":"## 下次遇到类似局面\\n铺出衍生物就立刻转化。"},
                  {"file_path":"card:123","content":"无关的卡牌片段"},
                  {"file_path":"rulebook_v10_en_p1-p2","content":"无关的规则片段"}
                ]}}
                """;
        DuelRecallTool tool = new DuelRecallTool(new LightRagSettings("http://localhost:9621", "", 8_000), "mix", 4);
        String out = tool.render(
                JsonMapper.builder().build().readTree(json), 4);

        assertTrue(out.contains("一句话结论"), out);
        assertTrue(out.contains("失误与代价"), out);
        assertTrue(out.contains("下次遇到类似局面"), out);
        assertTrue(out.contains("[历史对局 1]"), out);
        assertFalse(out.contains("[历史对局 2]"), "同一局的多个 chunk 不该算成两局：" + out);
        assertFalse(out.contains("无关的"), out);
    }
}
