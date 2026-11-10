package com.github.coldgust.ygoagent.decision.planexec;

import com.github.coldgust.ygoagent.decision.model.RequestKind;
import com.github.coldgust.ygodomain.duel.PlayerSide;
import com.github.coldgust.ygodomain.zone.ZoneKind;
import com.github.coldgust.ygodomain.zone.ZoneRef;
import com.github.coldgust.ygoagent.decision.plan.PlanStep;
import com.github.coldgust.ygoagent.decision.planexec.planner.PlanTreeParser;
import com.github.coldgust.ygoagent.decision.planexec.planner.PlannerSession;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanTreeParserTest {

    private static final String PLAN_JSON = """
            {
              "thinking": "空场保持；先交闪检索。",
              "goal": "建立循环",
              "main_line": {
                "assumes": {"phase": "MAIN1", "opp_monsters": "<=1"},
                "steps": [
                  {"when": "MAIN1", "verb": "activate", "passcode": 25048511, "why": "检索"},
                  {"when": "MAIN1", "verb": "summon", "passcode": 75982077, "why": "铺场"},
                  {"when": "EP", "verb": "end_turn", "why": "结束"}
                ],
                "branches": [
                  {"if": {"description": "交闪被无效"}, "steps": [
                      {"when": "MAIN1", "verb": "setspelltrap", "passcode": 98266377, "why": "改盖后场"}]}
                ]
              },
              "confidence": 0.7
            }
            """;

    @Test
    void parseValidPlan() {
        PlanTreeParser.ParsedPlan plan = PlanTreeParser.parse(PLAN_JSON);
        assertNotNull(plan);
        assertEquals("建立循环", plan.goal());
        assertEquals(3, plan.steps().size());
        assertEquals(1, plan.branches().size());
        assertEquals(0.7, plan.confidence(), 1e-9);
        PlanStep first = plan.steps().getFirst();
        assertEquals(25048511, first.alternatives().getFirst().passcode());
        assertEquals("activate", first.alternatives().getFirst().verb());
        // EP + end_turn → MAIN_PHASE（结束回合是主阶段请求的意图）
        assertEquals(RequestKindOf(first), RequestKind.MAIN_PHASE);
    }

    private static RequestKind RequestKindOf(PlanStep step) {
        return step.expectedRequest();
    }

    @Test
    void rejectUnknownVerb() {
        String bad = """
                {"main_line": {"steps": [{"when": "MAIN1", "verb": "win_game", "passcode": 1}]}}
                """;
        assertNull(PlanTreeParser.parse(bad));
    }

    @Test
    void passcodeMissingForCardVerbIsAcceptedAsWildcard() {
        // 语义已调整：缺卡号 = 通配（可能是还没检索到手的卡），不再整份拒绝；
        // 只有"给了卡号但卡库没有"才判编造。见 missingPasscodeIsWildcardNotAFailure / fabricatedPasscodeRejectsThePlan
        String raw = """
                {"main_line": {"steps": [{"when": "MAIN1", "verb": "activate"}]}}
                """;
        PlanTreeParser.ParsedPlan plan = PlanTreeParser.parse(raw);
        assertNotNull(plan);
        assertEquals(0L, plan.steps().getFirst().alternatives().getFirst().passcode());
    }

    @Test
    void acceptFlagVerbWithoutPasscode() {
        String ok = """
                {"main_line": {"steps": [{"when": "EP", "verb": "end_turn"}]}}
                """;
        PlanTreeParser.ParsedPlan plan = PlanTreeParser.parse(ok);
        assertNotNull(plan);
        assertEquals(1, plan.steps().size());
    }

    @Test
    void malformedJsonRejected() {
        assertNull(PlanTreeParser.parse("not a json {"));
    }

    @Test
    void acceptsExplicitHoldPlan() {
        // 对方回合"按住手坑"是合法计划：prompt 允许 steps/branches 皆空
        String raw = """
                {"thinking":"对方空场展开，按住灰流丽与G","goal":"保留资源过回合",
                 "main_line":{"steps":[],"branches":[]},"confidence":0.6}
                """;
        PlanTreeParser.Result result = PlanTreeParser.parseDetailed(raw);
        assertNotNull(result.plan(), "显式空计划不该判失败: " + result.failure());
        assertEquals(0, result.plan().steps().size());
        assertEquals(0, result.plan().branches().size());
        assertEquals("保留资源过回合", result.plan().goal());
    }

    @Test
    void rejectsPlanWithoutStepsOrBranchesFields() {
        assertNull(PlanTreeParser.parse("{\"thinking\":\"随便说说\",\"goal\":\"无\"}"),
                "压根没按 schema 输出（缺 steps/branches 字段）仍应拒绝");
    }

    @Test
    void parseUrgentChoice() {
        var draft = PlannerSession.ChoiceDraftParser.parse(
                "{\"choice\": \"C3\", \"rationale\": \"检索优先\", \"confidence\": 0.66}");
        assertTrue(draft.isPresent());
        assertEquals("C3", draft.get().choice().value());
        assertEquals("检索优先", draft.get().rationale());
        assertEquals(0.66, draft.get().confidence(), 1e-9);
    }


    @Test
    void parsesFencedJsonWithChitchat() {
        // 实机日志里的典型形态：寒暄 + ```json 围栏 + 结尾一句话
        String raw = """
                好的，我先看局面再给计划：
                ```json
                {"thinking":"空场先检索","goal":"建立循环","main_line":{"steps":[
                  {"when":"MAIN1","verb":"activate","passcode":25048511,"why":"检索"}]},"confidence":0.6}
                ```
                以上计划如需调整请告知。
                """;
        PlanTreeParser.ParsedPlan plan = PlanTreeParser.parse(raw);
        assertNotNull(plan, "带围栏/寒暄的输出必须能解析");
        assertEquals(1, plan.steps().size());
        assertEquals("建立循环", plan.goal());
    }

    @Test
    void parsesChineseVerbsAndStringPasscode() {
        String raw = """
                {"main_line":{"steps":[
                  {"when":"主要阶段1","verb":"发动","passcode":"25048511","why":"检索"},
                  {"when":"主要阶段1","verb":"通常召唤","passcode":75982077,"why":"铺场"},
                  {"when":"主要阶段1","verb":"盖放","passcode":98266377,"why":"后场"},
                  {"when":"结束阶段","verb":"结束回合","why":"过"}]}}
                """;
        PlanTreeParser.ParsedPlan plan = PlanTreeParser.parse(raw);
        assertNotNull(plan);
        assertEquals(List.of("activate", "summon", "setspelltrap", "end_turn"),
                plan.steps().stream().map(s -> s.alternatives().getFirst().verb()).toList());
        assertEquals(25048511L, plan.steps().getFirst().alternatives().getFirst().passcode());
    }

    @Test
    void rejectsWholePlanWhenOneStepBreaksTheContract() {
        // 主计划里出现非法步 = 模型没守契约，整份计划不可信（交回 one-shot 用完整局面重答）
        String raw = """
                {"main_line":{"steps":[
                  {"when":"MAIN1","verb":"activate","passcode":25048511},
                  {"when":"MAIN1","verb":"win_game","passcode":1},
                  {"when":"EP","verb":"end_turn"}]}}
                """;
        PlanTreeParser.Result result = PlanTreeParser.parseDetailed(raw);
        assertNull(result.plan());
        assertTrue(result.failure().contains("未知动词"), "失败原因要具体: " + result.failure());
        assertTrue(result.failure().contains("第 2 步"), "要指出第几步: " + result.failure());
    }

    @Test
    void linkStepMayOmitPasscode() {
        // LINK 目标在额外卡组，锚点看不到；passcode 0 由接地层按动词通配
        String raw = """
                {"main_line":{"steps":[
                  {"when":"MAIN1","verb":"summon","passcode":26077387},
                  {"when":"MAIN1","verb":"link","why":"两体做闪刀姬LINK"}]}}
                """;
        PlanTreeParser.ParsedPlan plan = PlanTreeParser.parse(raw);
        assertNotNull(plan, "LINK 步骤缺卡号不应判整份非法");
        assertEquals(2, plan.steps().size());
        assertEquals(0L, plan.steps().get(1).alternatives().getFirst().passcode());
    }

    @Test
    void missingPasscodeIsWildcardNotAFailure() {
        String raw = """
                {"thinking":"交闪检索零衣后通召","goal":"建立交闪循环","main_line":{"steps":[
                  {"when":"MAIN1","verb":"activate","passcode":63166095,"why":"检索零衣"},
                  {"when":"MAIN1","verb":"summon","passcode":0,"why":"通召零衣"},
                  {"when":"MAIN1","verb":"link","passcode":63288574,"why":"零衣变燎里"}]}}
                """;
        PlanTreeParser.Result result = PlanTreeParser.parseDetailed(raw, code -> true);
        assertNotNull(result.plan(), "缺卡号不该判整份非法: " + result.failure());
        assertEquals(3, result.plan().steps().size());
        assertEquals(0L, result.plan().steps().get(1).alternatives().getFirst().passcode());
        assertEquals(Boolean.TRUE, result.plan().steps().get(1).expectedEffects().get("wildcardPasscode"),
                "缺卡号的步骤要打标，便于 trace 观察接地质量");
    }

    @Test
    void unknownPasscodeIsFlaggedNotRejected() {
        // 卡库可能滞后于新卡：查不到只打标，不拒绝（拒绝会连累整份计划）。
        // 真接不上地时由"计划作废 + 重规划"兜住，比误杀整份安全。
        String raw = """
                {"main_line":{"steps":[
                  {"when":"MAIN1","verb":"summon","passcode":123456789},
                  {"when":"MAIN1","verb":"activate","passcode":63166095}]}}
                """;
        PlanTreeParser.Result result = PlanTreeParser.parseDetailed(raw, code -> code == 63166095L);
        assertNotNull(result.plan(), "卡号查不到不该判整份非法: " + result.failure());
        assertEquals(2, result.plan().steps().size());
        assertEquals(Boolean.TRUE, result.plan().steps().getFirst().expectedEffects().get("unknownPasscode"));
        assertNull(result.plan().steps().get(1).expectedEffects().get("unknownPasscode"),
                "卡库已有的卡不该被打标");
    }

    @Test
    void repairsTruncatedJson() {
        // 被 max_tokens 截断：末步没写完、括号未闭合
        String raw = "{\"thinking\":\"空场先检索，随后盖后场\",\"main_line\":{\"steps\":["
                + "{\"when\":\"MAIN1\",\"verb\":\"activate\",\"passcode\":25048511},"
                + "{\"when\":\"MAIN1\",\"verb\":\"setspelltrap\",\"passcode\":98266377";
        PlanTreeParser.ParsedPlan plan = PlanTreeParser.parse(raw);
        assertNotNull(plan, "截断的 JSON 应能被补齐后解析");
        assertEquals(2, plan.steps().size());
    }

    @Test
    void unwrapsPlanEnvelope() {
        String raw = """
                {"plan":{"thinking":"t","goal":"g","main_line":{"steps":[
                  {"when":"MAIN1","verb":"activate","passcode":25048511}]}}}
                """;
        PlanTreeParser.ParsedPlan plan = PlanTreeParser.parse(raw);
        assertNotNull(plan);
        assertEquals("g", plan.goal());
    }

    @Test
    void branchStepsMayOmitPasscode() {
        // 对方回合的防守计划：分支里常写"盖后场"而不给卡号
        String raw = """
                {"main_line":{"steps":[],"branches":[
                  {"if":{"description":"对手特召"},"steps":[{"when":"CHAIN","verb":"activate","why":"丢灰流丽"}]}]}}
                """;
        PlanTreeParser.ParsedPlan plan = PlanTreeParser.parse(raw);
        assertNotNull(plan, "防守计划的分支不能因为缺卡号被丢弃");
        assertEquals(1, plan.branches().size());
        assertEquals(0, plan.steps().size());
    }

    @Test
    void stepZoneIsParsedIntoActionSpec() {
        // 位置是决策点：计划里的格子必须解析成具体区域，才能在执行时兑现
        String raw = """
                {"main_line":{"steps":[
                  {"when":"MAIN1","verb":"summon","passcode":75982077,"zone":"MMZ-3","why":"抢纵列"},
                  {"when":"MAIN1","verb":"setspelltrap","passcode":98266377,"zone":"我方魔陷区#2"},
                  {"when":"MAIN1","verb":"link","zone":{"owner":"self","kind":"extraMonster","sequence":1}},
                  {"when":"MAIN1","verb":"setspelltrap","passcode":98266377}]}}
                """;
        PlanTreeParser.ParsedPlan plan = PlanTreeParser.parse(raw);
        assertNotNull(plan);
        assertEquals(ZoneRef.mainMonster(PlayerSide.SELF, 2),
                plan.steps().get(0).alternatives().getFirst().zone(),
                "MMZ-3 = 我方主怪兽区第 3 格（序号 1 起）");
        assertEquals(ZoneRef.spellTrap(PlayerSide.SELF, 1),
                plan.steps().get(1).alternatives().getFirst().zone());
        assertEquals(new ZoneRef(ZoneKind.EXTRA_MONSTER_ZONE, PlayerSide.SELF, 0),
                plan.steps().get(2).alternatives().getFirst().zone(),
                "对象写法的 sequence 也按 1 起算");
        assertNull(plan.steps().get(3).alternatives().getFirst().zone(), "没写 zone 就是 null，不是失败");
    }

    @Test
    void unreadableZoneOnlyDropsTheZoneNotTheStep() {
        // 位置写错（"中间那格"）远比动作写错轻：丢位置、保步骤
        String raw = """
                {"main_line":{"steps":[{"when":"MAIN1","verb":"summon","passcode":75982077,"zone":"中间那格"}]}}
                """;
        PlanTreeParser.ParsedPlan plan = PlanTreeParser.parse(raw);
        assertNotNull(plan, "位置读不懂不该让整份计划作废");
        assertNull(plan.steps().getFirst().alternatives().getFirst().zone());
        assertTrue(plan.steps().getFirst().expectedEffects().containsKey("zoneUnparsed"),
                "要打标，便于复盘模型是不是不会写 zone");
    }

    @Test
    void placeStepTargetsThePlaceRequest() {
        String raw = """
                {"main_line":{"steps":[{"verb":"place","zone":"STZ-4","why":"盖最右留灵摆位"}]}}
                """;
        PlanTreeParser.ParsedPlan plan = PlanTreeParser.parse(raw);
        assertNotNull(plan);
        assertEquals(RequestKind.PLACE,
                plan.steps().getFirst().expectedRequest(),
                "place 动作默认落在位置请求上");
    }

    @Test
    void urgentChoiceParsesEvenWithoutCandidateContext() {
        var draft = PlannerSession.ChoiceDraftParser.parse("{\"choice\": \"C99\", \"confidence\": 0.5}");
        assertTrue(draft.isPresent());
        assertEquals("C99", draft.get().choice().value());
        assertTrue(PlannerSession.ChoiceDraftParser.parse("垃圾输出").isEmpty());
    }

    @Test
    void advisorOutputBecomesASkeletonPlanWithNarrativeOnly() {
        // 顾问模式（PLANNER_PATH_PLANNING=false）：模型只给分析/战术/combo，不给步骤。
        // 解析后必须是一份"没有步骤"的骨架计划，三段话拼进 thinking 供执行器参考。
        String json = """
                {
                  "analysis": "主怪区空，墓地魔法 0，通召未用；对手后场 1 张未知。",
                  "goal": "建立交闪循环并保持空场",
                  "tactics": "先用交闪找零衣；若被灰流丽就转盖后场，留手坑过回合。",
                  "combo": "交闪找零衣，通召零衣站中间列，战刀机留手上别急着盖。",
                  "confidence": 0.7
                }
                """;

        PlanTreeParser.ParsedPlan plan = PlanTreeParser.parse(json);

        assertNotNull(plan, "顾问模式产出必须能解析成计划");
        assertTrue(plan.steps().isEmpty(), "顾问模式不应产出可执行步骤");
        assertTrue(plan.branches().isEmpty(), "顾问模式不应产出分支");
        assertEquals("建立交闪循环并保持空场", plan.goal());
        assertTrue(plan.thinking().contains("场面："), plan.thinking());
        assertTrue(plan.thinking().contains("战术："), plan.thinking());
        assertTrue(plan.thinking().contains("combo："), plan.thinking());
        assertTrue(plan.thinking().contains("零衣"), plan.thinking());
    }

    @Test
    void advisorOptionsAreRenderedWithPickMarked() {
        // "最优打法"必须来自比较：不列方案、只给结论，执行器就只能凭直觉走。
        String json = """
                {
                  "analysis": "对手有 2500 打点，我方主怪区被零衣占住。",
                  "goal": "压制",
                  "options": [
                    {"line": "解放零衣出飒天直击后再交闪", "gain": "1500 伤害 + 空出主怪区",
                     "risk": "飒天会被 2500 战破", "pick": true, "why": "唯一同时推进伤害与资源"},
                    {"line": "零衣不解放直接过", "gain": "资源全留", "risk": "本回合零伤害",
                     "pick": false, "why": "把主动权交出去"}
                  ],
                  "tactics": "主打第一条线，交闪检索完就停。",
                  "combo": "解放零衣→飒天直击→交闪检索。",
                  "confidence": 0.7
                }
                """;

        PlanTreeParser.ParsedPlan plan = PlanTreeParser.parse(json);

        assertNotNull(plan, "带 options 的顾问产出要能解析");
        assertTrue(plan.thinking().contains("方案比较："), plan.thinking());
        assertTrue(plan.thinking().contains("①[选]"), "选中项要标出来:\n" + plan.thinking());
        assertTrue(plan.thinking().contains("②[否]"), "淘汰项也要在:\n" + plan.thinking());
        assertTrue(plan.thinking().contains("收益：1500 伤害"), plan.thinking());
    }

    @Test
    void advisoryKeepsMultiStepLinesAndResourceAccounting() {
        // 规划器被要求"算到收手点"后，最自然的输出是动作序列数组 + 资源盘点；
        // 只认字符串会把整条线渲染成空行，计划看起来又退回"只有一句话、1 步就停"。
        String json = """
                {
                  "analysis": "双方空场，墓地魔法 0 张。",
                  "resources": "通召 1 次、额外可特召 2 次、交闪能检索 1 张、墓地魔法 0→1",
                  "goal": "展开到雫空站场并把墓地魔法堆到 1 张",
                  "options": [
                    {"line": ["交闪检索大黄蜂", "大黄蜂出衍生物", "衍生物连接召唤雫空", "雫空回收墓地魔法"],
                     "gain": "墓地魔法 0→1，EMZ 有 2000 打点", "risk": "被灰则只剩一张交闪进墓",
                     "pick": true, "why": "把两张手牌换成场面+资源"},
                    {"line": ["只盖连刀过"], "gain": "资源全留", "risk": "本回合零展开",
                     "pick": false, "why": "放弃唯一的展开窗口"}
                  ],
                  "tactics": "走第一条线，做到雫空回收就停。",
                  "combo": "①交闪检索大黄蜂 ②大黄蜂出衍生物 ③连接召唤雫空 ④回收墓地魔法",
                  "confidence": 0.7
                }
                """;

        PlanTreeParser.ParsedPlan plan = PlanTreeParser.parse(json);

        assertNotNull(plan, "带动作序列的顾问产出要能解析");
        String thinking = plan.thinking();
        assertTrue(thinking.contains("资源："), "资源盘点要进上下文:\n" + thinking);
        assertTrue(thinking.contains("交闪检索大黄蜂 → 大黄蜂出衍生物 → 衍生物连接召唤雫空 → 雫空回收墓地魔法"),
                "动作序列要按 A → B → C 连起来，不能丢掉:\n" + thinking);
        assertTrue(thinking.contains("只盖连刀过"), "保守线也要保留:\n" + thinking);
    }
}
