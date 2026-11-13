package com.github.coldgust.ygoagent.pipeline.plan;

import com.github.coldgust.ygoagent.model.RequestKind;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.zone.ZoneKind;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoagent.pipeline.plan.PlanAnswer.BranchDraft;
import com.github.coldgust.ygoagent.pipeline.plan.PlanAnswer.OptionDraft;
import com.github.coldgust.ygoagent.pipeline.plan.PlanAnswer.StepDraft;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规划器结构化输出 → TurnPlan 的语义校验（JSON 提取/解析归框架，这里只测类型化的 PlanAnswer 组装）。
 */
class PlanAssemblerTest {

    private static StepDraft step(String when, String verb, Long passcode, Object zone, String why) {
        return new StepDraft(verb, passcode, zone, when, why);
    }

    private static PlanAnswer plan(List<StepDraft> steps, List<BranchDraft> branches) {
        return new PlanAnswer(false, 0.7, "空场保持；先交闪检索。", "建立循环",
                new PlanAnswer.MainLine(steps, branches), null, null, null, null, null, null);
    }

    private static PlanAssembler.Result assemble(PlanAnswer answer, java.util.function.LongPredicate knownCard) {
        return PlanAssembler.assemble(answer, knownCard);
    }

    @Test
    void assembleValidPlan() {
        PlanAssembler.Result result = assemble(plan(
                List.of(
                        step("MAIN1", "activate", 25048511L, null, "检索"),
                        step("MAIN1", "summon", 75982077L, null, "铺场"),
                        step("EP", "end_turn", null, null, "结束")),
                List.of(new BranchDraft("交闪被无效",
                        Map.of("description", "交闪被无效"),
                        List.of(step("MAIN1", "setspelltrap", 98266377L, null, "改盖后场"))))),
                null);
        assertNotNull(result.plan());
        PlanAssembler.Parsed parsed = result.plan();
        assertEquals("建立循环", parsed.goal());
        assertEquals(3, parsed.steps().size());
        assertEquals(1, parsed.branches().size());
        assertEquals(0.7, parsed.confidence(), 1e-9);
        PlanStep first = parsed.steps().getFirst();
        assertEquals(25048511L, first.alternatives().getFirst().passcode());
        assertEquals("activate", first.alternatives().getFirst().verb());
        // EP + end_turn → MAIN_PHASE（结束回合是主阶段请求的意图）
        assertEquals(RequestKind.MAIN_PHASE, first.expectedRequest());
    }

    @Test
    void rejectUnknownVerb() {
        PlanAssembler.Result result = assemble(plan(
                List.of(step("MAIN1", "win_game", 1L, null, null)), List.of()), null);
        assertNull(result.plan());
        assertTrue(result.failure().contains("未知动词"), result.failure());
    }

    @Test
    void passcodeMissingForCardVerbIsAcceptedAsWildcard() {
        // 缺卡号 = 通配（可能是还没检索到手的卡），不再整份拒绝；只有"给了卡号但卡库没有"才打标
        PlanAssembler.Result result = assemble(plan(
                List.of(step("MAIN1", "activate", null, null, null)), List.of()), null);
        assertNotNull(result.plan());
        assertEquals(0L, result.plan().steps().getFirst().alternatives().getFirst().passcode());
    }

    @Test
    void acceptsExplicitHoldPlan() {
        // 对方回合"按住手坑"是合法计划：mainLine 显式给了空数组（≠ 未按 schema 输出的 null）
        PlanAssembler.Result result = assemble(plan(List.of(), List.of()), null);
        assertNotNull(result.plan(), "显式空计划不该判失败: " + result.failure());
        assertEquals(0, result.plan().steps().size());
        assertEquals(0, result.plan().branches().size());
    }

    @Test
    void rejectsPlanWithoutStepsOrBranchesFields() {
        // mainLine 整个缺失且没有顾问段 = 压根没按 schema 输出
        PlanAssembler.Result result = assemble(
                new PlanAnswer(false, 0.5, "随便说说", "无", null, null, null, null, null, null, null),
                null);
        assertNull(result.plan(), "缺 main_line 且无顾问段仍应拒绝");
    }

    @Test
    void unwrapsPlanEnvelope() {
        PlanAnswer inner = plan(
                List.of(step("MAIN1", "activate", 25048511L, null, null)), List.of());
        PlanAnswer enveloped = new PlanAnswer(null, null, null, null, null,
                null, null, null, null, null, inner);
        PlanAssembler.Result result = assemble(enveloped, null);
        assertNotNull(result.plan());
        assertEquals("建立循环", result.plan().goal());
    }

    @Test
    void linkStepMayOmitPasscode() {
        // LINK 目标在额外卡组，锚点看不到；passcode 0 由接地层按动词通配
        PlanAssembler.Result result = assemble(plan(
                List.of(
                        step("MAIN1", "summon", 26077387L, null, null),
                        step("MAIN1", "link", null, null, "两体做闪刀姬LINK")),
                List.of()), null);
        assertNotNull(result.plan(), "LINK 步骤缺卡号不应判整份非法");
        assertEquals(2, result.plan().steps().size());
        assertEquals(0L, result.plan().steps().get(1).alternatives().getFirst().passcode());
    }

    @Test
    void missingPasscodeIsWildcardNotAFailure() {
        PlanAssembler.Result result = assemble(plan(
                List.of(
                        step("MAIN1", "activate", 63166095L, null, "检索零衣"),
                        step("MAIN1", "summon", 0L, null, "通召零衣"),
                        step("MAIN1", "link", 63288574L, null, "零衣变燎里")),
                List.of()), code -> true);
        assertNotNull(result.plan(), "缺卡号不该判整份非法: " + result.failure());
        assertEquals(3, result.plan().steps().size());
        assertEquals(Boolean.TRUE, result.plan().steps().get(1).expectedEffects().get("wildcardPasscode"),
                "缺卡号的步骤要打标，便于 trace 观察接地质量");
    }

    @Test
    void unknownPasscodeIsFlaggedNotRejected() {
        // 卡库可能滞后于新卡：查不到只打标，不拒绝（拒绝会连累整份计划）
        PlanAssembler.Result result = assemble(plan(
                List.of(
                        step("MAIN1", "summon", 123456789L, null, null),
                        step("MAIN1", "activate", 63166095L, null, null)),
                List.of()), code -> code == 63166095L);
        assertNotNull(result.plan(), "卡号查不到不该判整份非法: " + result.failure());
        assertEquals(2, result.plan().steps().size());
        assertEquals(Boolean.TRUE, result.plan().steps().getFirst().expectedEffects().get("unknownPasscode"));
        assertNull(result.plan().steps().get(1).expectedEffects().get("unknownPasscode"),
                "卡库已有的卡不该被打标");
    }

    @Test
    void chineseVerbsAndWhenAreNormalized() {
        PlanAssembler.Result result = assemble(plan(
                List.of(
                        step("主要阶段1", "发动", 25048511L, null, "检索"),
                        step("主要阶段1", "通常召唤", 75982077L, null, "铺场"),
                        step("主要阶段1", "盖放", 98266377L, null, "后场"),
                        step("结束阶段", "结束回合", null, null, "过")),
                List.of()), null);
        assertNotNull(result.plan());
        assertEquals(List.of("activate", "summon", "setspelltrap", "end_turn"),
                result.plan().steps().stream().map(s -> s.alternatives().getFirst().verb()).toList());
        assertEquals(25048511L, result.plan().steps().getFirst().alternatives().getFirst().passcode());
    }

    @Test
    void branchStepsMayOmitPasscode() {
        // 对方回合的防守计划：分支里常写"盖后场"而不给卡号
        PlanAssembler.Result result = assemble(plan(
                List.of(),
                List.of(new BranchDraft("对手特召", Map.of("description", "对手特召"),
                        List.of(step("CHAIN", "activate", null, null, "丢灰流丽"))))),
                null);
        assertNotNull(result.plan(), "防守计划的分支不能因为缺卡号被丢弃");
        assertEquals(1, result.plan().branches().size());
        assertEquals(0, result.plan().steps().size());
    }

    @Test
    void stepZoneIsParsedIntoActionSpec() {
        // 位置是决策点：计划里的格子必须解析成具体区域，才能在执行时兑现
        PlanAssembler.Result result = assemble(plan(
                List.of(
                        step("MAIN1", "summon", 75982077L, "MMZ-3", "抢纵列"),
                        step("MAIN1", "setspelltrap", 98266377L, "我方魔陷区#2", null),
                        step("MAIN1", "link", null,
                                Map.of("owner", "self", "kind", "extraMonster", "sequence", 1), null),
                        step("MAIN1", "setspelltrap", 98266377L, null, null)),
                List.of()), null);
        assertNotNull(result.plan());
        assertEquals(ZoneRef.mainMonster(PlayerSide.SELF, 2),
                result.plan().steps().get(0).alternatives().getFirst().zone(),
                "MMZ-3 = 我方主怪兽区第 3 格（序号 1 起）");
        assertEquals(ZoneRef.spellTrap(PlayerSide.SELF, 1),
                result.plan().steps().get(1).alternatives().getFirst().zone());
        assertEquals(new ZoneRef(ZoneKind.EXTRA_MONSTER_ZONE, PlayerSide.SELF, 0),
                result.plan().steps().get(2).alternatives().getFirst().zone(),
                "对象写法的 sequence 也按 1 起算");
        assertNull(result.plan().steps().get(3).alternatives().getFirst().zone(),
                "没写 zone 就是 null，不是失败");
    }

    @Test
    void unreadableZoneOnlyDropsTheZoneNotTheStep() {
        // 位置写错（"中间那格"）远比动作写错轻：丢位置、保步骤
        PlanAssembler.Result result = assemble(plan(
                List.of(step("MAIN1", "summon", 75982077L, "中间那格", null)), List.of()), null);
        assertNotNull(result.plan(), "位置读不懂不该让整份计划作废");
        assertNull(result.plan().steps().getFirst().alternatives().getFirst().zone());
        assertTrue(result.plan().steps().getFirst().expectedEffects().containsKey("zoneUnparsed"),
                "要打标，便于复盘模型是不是不会写 zone");
    }

    @Test
    void placeStepTargetsThePlaceRequest() {
        PlanAssembler.Result result = assemble(plan(
                List.of(new StepDraft("place", null, "STZ-4", null, "盖最右留灵摆位")), List.of()), null);
        assertNotNull(result.plan());
        assertEquals(RequestKind.PLACE, result.plan().steps().getFirst().expectedRequest(),
                "place 动作默认落在位置请求上");
    }

    @Test
    void advisorOutputBecomesASkeletonPlanWithNarrativeOnly() {
        // 顾问模式（PLANNER_PATH_PLANNING=false）：模型只给分析/战术/combo，不给步骤。
        // 组装后必须是一份"没有步骤"的骨架计划，各段拼进 thinking 供执行器参考。
        PlanAnswer advisory = new PlanAnswer(false, 0.7, null,
                "建立交闪循环并保持空场", null,
                "主怪区空，墓地魔法 0，通召未用；对手后场 1 张未知。", null, null,
                "先用交闪找零衣；若被灰流丽就转盖后场，留手坑过回合。",
                "交闪找零衣，通召零衣站中间列，战刀机留手上别急着盖。", null);

        PlanAssembler.Result result = assemble(advisory, null);

        assertNotNull(result.plan(), "顾问模式产出必须能解析成计划");
        assertTrue(result.plan().steps().isEmpty(), "顾问模式不应产出可执行步骤");
        assertTrue(result.plan().branches().isEmpty(), "顾问模式不应产出分支");
        assertEquals("建立交闪循环并保持空场", result.plan().goal());
        assertTrue(result.plan().thinking().contains("场面："), result.plan().thinking());
        assertTrue(result.plan().thinking().contains("战术："), result.plan().thinking());
        assertTrue(result.plan().thinking().contains("combo："), result.plan().thinking());
        assertTrue(result.plan().thinking().contains("零衣"), result.plan().thinking());
    }

    @Test
    void advisorOptionsAreRenderedWithPickMarked() {
        // "最优打法"必须来自比较：不列方案、只给结论，执行器就只能凭直觉走。
        PlanAnswer advisory = new PlanAnswer(false, 0.7, null, "压制", null,
                "对手有 2500 打点，我方主怪区被零衣占住。", null,
                List.of(
                        new OptionDraft(List.of("解放零衣出飒天直击后再交闪"), "1500 伤害 + 空出主怪区",
                                "飒天会被 2500 战破", true, "唯一同时推进伤害与资源"),
                        new OptionDraft(List.of("零衣不解放直接过"), "资源全留", "本回合零伤害",
                                false, "把主动权交出去")),
                "主打第一条线，交闪检索完就停。",
                "解放零衣→飒天直击→交闪检索。", null);

        PlanAssembler.Result result = assemble(advisory, null);

        assertNotNull(result.plan(), "带 options 的顾问产出要能解析");
        assertTrue(result.plan().thinking().contains("方案比较："), result.plan().thinking());
        assertTrue(result.plan().thinking().contains("①[选] "), "选中项要标出来:\n" + result.plan().thinking());
        assertTrue(result.plan().thinking().contains("②[否] "), "淘汰项也要在:\n" + result.plan().thinking());
        assertTrue(result.plan().thinking().contains("收益：1500 伤害"), result.plan().thinking());
    }

    @Test
    void advisoryKeepsMultiStepLinesAndResourceAccounting() {
        // 规划器被要求"算到收手点"后，最自然的输出是动作序列数组 + 资源盘点
        PlanAnswer advisory = new PlanAnswer(false, 0.7, null,
                "展开到雫空站场并把墓地魔法堆到 1 张", null,
                "双方空场，墓地魔法 0 张。",
                "通召 1 次、额外可特召 2 次、交闪能检索 1 张、墓地魔法 0→1",
                List.of(
                        new OptionDraft(List.of("交闪检索大黄蜂", "大黄蜂出衍生物", "衍生物连接召唤雫空",
                                        "雫空回收墓地魔法"),
                                "墓地魔法 0→1，EMZ 有 2000 打点", "被灰则只剩一张交闪进墓",
                                true, "把两张手牌换成场面+资源"),
                        new OptionDraft(List.of("只盖连刀过"), "资源全留", "本回合零展开",
                                false, "放弃唯一的展开窗口")),
                "走第一条线，做到雫空回收就停。",
                "①交闪检索大黄蜂 ②大黄蜂出衍生物 ③连接召唤雫空 ④回收墓地魔法", null);

        PlanAssembler.Result result = assemble(advisory, null);

        assertNotNull(result.plan(), "带动作序列的顾问产出要能解析");
        String thinking = result.plan().thinking();
        assertTrue(thinking.contains("资源："), "资源盘点要进上下文:\n" + thinking);
        assertTrue(thinking.contains("交闪检索大黄蜂 → 大黄蜂出衍生物 → 衍生物连接召唤雫空 → 雫空回收墓地魔法"),
                "动作序列要按 A → B → C 连起来，不能丢掉:\n" + thinking);
        assertTrue(thinking.contains("只盖连刀过"), "保守线也要保留:\n" + thinking);
    }

    @Test
    void singleStringLineIsAcceptedLikeAnArray() throws Exception {
        // 模型偶尔把 line 写成单个字符串——Jackson @JsonCreator 收编两态（走与生产一致的解析路径）
        String json = """
                {"confidence": 0.7, "goal": "压制", "options": [
                  {"line": "解放零衣出飒天直击后再交闪", "gain": "伤害", "risk": "战破", "pick": true}],
                 "tactics": "主打这条线"}
                """;
        PlanAnswer advisory = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(json, PlanAnswer.class);
        PlanAssembler.Result result = assemble(advisory, null);
        assertNotNull(result.plan());
        assertTrue(result.plan().thinking().contains("解放零衣出飒天直击后再交闪"),
                result.plan().thinking());
    }
}
