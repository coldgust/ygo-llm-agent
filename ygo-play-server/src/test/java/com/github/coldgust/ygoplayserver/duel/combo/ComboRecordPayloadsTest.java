package com.github.coldgust.ygoplayserver.duel.combo;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * wire 契约回归(D8):迁移把 {@code Map<String,Object>} 换成 record 后,JSON 键必须逐键不变。
 * 前端消费点见 {@code web/src/lib/api.ts} 与 {@code ComboView.svelte}。
 */
class ComboRecordPayloadsTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void viewKeepsRestKeys() {
        ComboRecordView view = new ComboRecordView("duel-1", 7L, "闪刀", 2,
                List.of("第一步", "第二步"), "摘要", "零衣,交闪", "LLM");

        String json = MAPPER.writeValueAsString(view);

        for (String key : List.of("duelId", "recordId", "archetype", "count",
                "stepPreviews", "summary", "keyCards", "summarySource")) {
            assertTrue(json.contains("\"" + key + "\""), "缺少键 " + key + ": " + json);
        }
    }

    @Test
    void viewSerializesNullsSoFrontendContractStaysStable() {
        ComboRecordView view = new ComboRecordView("duel-1", 7L, "闪刀", 0,
                List.of(), null, null, "NONE");

        String json = MAPPER.writeValueAsString(view);

        assertTrue(json.contains("\"summary\":null"), json);
        assertTrue(json.contains("\"keyCards\":null"), json);
    }

    @Test
    void recordedEventHasNoKeyCardsKey() {
        ComboRecordedEvent event = new ComboRecordedEvent("duel-1", 7L, "闪刀", 2,
                List.of("第一步"), "摘要", "LLM");

        String json = MAPPER.writeValueAsString(event);

        assertFalse(json.contains("keyCards"), "成功事件不应多出 keyCards 键: " + json);
        assertTrue(json.contains("\"summary\":\"摘要\""));
        assertTrue(json.contains("\"summarySource\":\"LLM\""));
    }

    @Test
    void outcomeSerializesReasonAndErrorExclusively() {
        String skipped = MAPPER.writeValueAsString(
                new ComboRecordOutcome("duel-1", 0, "用户回合没有可记录的操作", null));
        assertTrue(skipped.contains("\"reason\""), skipped);
        assertFalse(skipped.contains("\"error\""), skipped);
        assertTrue(skipped.contains("\"count\":0"), skipped);

        String failed = MAPPER.writeValueAsString(
                new ComboRecordOutcome("duel-1", 0, null, "boom"));
        assertTrue(failed.contains("\"error\""), failed);
        assertFalse(failed.contains("\"reason\""), failed);
    }

    @Test
    void stepRowKeepsCamelCaseJsonbKeys() {
        ComboStepRow row = new ComboStepRow(1, 1, "MAIN1", "CARD_MOVED", "零衣登场",
                12345L, null, "MZONE", "ATTACK", "用户");

        String json = MAPPER.writeValueAsString(row);

        for (String key : List.of("seq", "turn", "phase", "kind", "desc", "passcode",
                "fromZone", "toZone", "position", "player")) {
            assertTrue(json.contains("\"" + key + "\""), "缺少键 " + key + ": " + json);
        }
    }
}