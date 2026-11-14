package com.github.coldgust.ygoagent.pipeline.judge;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词输出契约与解析 POJO 的一致性钉子（实机事故 2026-11-14：v5 提示词要求 verify 字段
 * 而 JudgeAnswer 没有，POJO 严格解析把每手决策打成"输出不可解析"落兜底）。
 *
 * <p>方案 A 定稿：字段级 JSON 示例不进 system——langchain4j 已从 POJO 生成格式指令追加到
 * 每条请求，POJO 是输出契约的唯一源。本钉子防止契约漂移回归。
 */
class JudgePromptsContractTest {

    private static final String SYSTEM = JudgePrompts.SYSTEM;

    @Test
    void systemPromptDoesNotCarryAFieldLevelJsonContract() {
        // system 里不再出现花括号包裹的字段级 JSON 示例（格式指令由框架从 POJO 生成）
        assertFalse(Pattern.compile("\\{\\s*\"choice\"").matcher(SYSTEM).find(),
                "system 不应再手写字段级 JSON 示例（漂移面）:\n"
                        + excerptAround(SYSTEM, "\"choice\""));
    }

    @Test
    void systemPromptDelegatesContractToTheFormatInstruction() {
        assertTrue(SYSTEM.contains("随请求附带的格式指令"),
                "输出契约应显式指向随请求附带的格式指令");
        assertFalse(SYSTEM.contains("verify"),
                "verify 字段已随契约对齐删除（写而不读的字段）:\n" + excerptAround(SYSTEM, "verify"));
    }

    @Test
    void systemPromptKeysReferencedInProseAllExistInThePojo() {
        // 散文里点名的键（choice/rationale/confidence/zone）必须是 POJO 的真实组件——
        // 防止"文字契约"与 POJO 再次漂移
        List<String> pojoFields = Arrays.stream(JudgeAnswer.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(Collectors.toList());
        for (String key : List.of("choice", "rationale", "confidence", "zone")) {
            assertTrue(pojoFields.contains(key), "提示词提到的键应在 POJO 中: " + key);
            assertTrue(SYSTEM.contains(key), "提示词应交代该键的语义: " + key);
        }
        // 反向：提示词里不得点名 POJO 之外的键（逐词扫 "xxx"：" 样式的键引用）
        Matcher m = Pattern.compile("\"([a-zA-Z]+)\"\\s*[:：]").matcher(SYSTEM);
        while (m.find()) {
            String key = m.group(1);
            assertTrue(pojoFields.contains(key) || key.equals("keep") || key.equals("zoneCode"),
                    "提示词引用了 POJO 之外的键 \"" + key + "\"（漂移）");
        }
    }

    private static String excerptAround(String text, String token) {
        int i = text.indexOf(token);
        if (i < 0) {
            return "";
        }
        return text.substring(Math.max(0, i - 120), Math.min(text.length(), i + 120));
    }

    @Test
    void judgeAnswerPojoFieldCountMatchesTheFourKeyContract() {
        assertEquals(4, JudgeAnswer.class.getRecordComponents().length,
                "JudgeAnswer 的组件数与散文契约（choice/rationale/confidence/zone）一致");
    }
}
