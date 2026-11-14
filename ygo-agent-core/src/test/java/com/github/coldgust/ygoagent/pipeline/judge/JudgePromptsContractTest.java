package com.github.coldgust.ygoagent.pipeline.judge;

import dev.langchain4j.model.output.structured.Description;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输出契约钉子（实机事故 2026-11-14：v5 提示词要求 verify 字段而 JudgeAnswer 没有，
 * POJO 严格解析把每手决策打成"输出不可解析"落兜底）。
 *
 * <p>2026-11-14 二次定稿（用户指令：所有提示词的输出契约用 LangChain4j 注解实现，
 * 不写在提示词里）：<b>契约唯一源 = {@link JudgeAnswer} 组件上的 {@code @Description} 注解</b>——
 * 框架从注解生成格式指令追加到每条请求；system 只保留行为纪律，不再重复字段语义。
 * 本钉子两方向防回归：① 每个组件都必须有 @Description（否则语义悄悄丢失）；
 * ② system 不得再手写字段级 JSON 契约。
 */
class JudgePromptsContractTest {

    private static final String SYSTEM = JudgePrompts.SYSTEM;

    @Test
    void systemPromptDoesNotCarryAFieldLevelJsonContract() {
        // system 里不再出现花括号包裹的字段级 JSON 示例（格式指令由框架从 POJO 注解生成）
        assertFalse(Pattern.compile("\\{\\s*\"choice\"").matcher(SYSTEM).find(),
                "system 不应手写字段级 JSON 示例（漂移面）:\n"
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
    void everyComponentHasAnAnnotationContract() throws Exception {
        // 契约唯一源：每个组件的语义都必须写在 @Description 里（进框架生成的格式指令）。
        // @Description 的 @Target 是 FIELD/TYPE（不含 RECORD_COMPONENT），注解落在后备字段上，
        // 反射按字段读（与框架 JsonSchemaElementUtils 的读取路径一致）。
        for (RecordComponent component : JudgeAnswer.class.getRecordComponents()) {
            Description description = annotationOf(JudgeAnswer.class, component.getName());
            assertNotNull(description, "组件缺少 @Description 契约: " + component.getName());
            assertTrue(String.join("", description.value()).length() >= 8,
                    "@Description 不应为空泛占位: " + component.getName());
        }
    }

    @Test
    void replanContractLivesInTheAnnotation() throws Exception {
        // 重规划判决（needReplan/replanReason）是编排层的触发信号，语义必须钉在注解里
        assertEquals(true, descriptionOf("needReplan").contains("重规划"),
                "needReplan 的注解应交代重规划触发语义");
        assertTrue(descriptionOf("needReplan").contains("choice"),
                "needReplan 的注解必须说明本手 choice 仍是必答项");
        assertTrue(descriptionOf("replanReason").contains("规划器"),
                "replanReason 的注解应说明原因会转达给规划器");
    }

    @Test
    void judgeAnswerCarriesSixComponentContract() {
        assertEquals(6, JudgeAnswer.class.getRecordComponents().length,
                "组件数与契约一致（choice/confidence/rationale/zone/needReplan/replanReason）");
        List<String> names = Arrays.stream(JudgeAnswer.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toList());
        assertTrue(names.containsAll(List.of("needReplan", "replanReason")),
                "重规划判决两字段必须存在（编排层按名读取）");
    }

    @Test
    void proseKeyReferencesAllExistInThePojo() {
        // 反向：system 散文里点名的键（"xxx"：" 样式）不得超出 POJO 组件（防契约漂移）
        List<String> pojoFields = Arrays.stream(JudgeAnswer.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toList());
        Matcher m = Pattern.compile("\"([a-zA-Z]+)\"\\s*[:：]").matcher(SYSTEM);
        while (m.find()) {
            String key = m.group(1);
            assertTrue(pojoFields.contains(key) || key.equals("zoneCode"),
                    "system 引用了 POJO 之外的键 \"" + key + "\"（漂移）");
        }
    }

    private static String descriptionOf(String component) throws Exception {
        return String.join("", annotationOf(JudgeAnswer.class, component).value());
    }

    /** @Description 只能标在字段上（@Target 无 RECORD_COMPONENT），从后备字段读。 */
    private static Description annotationOf(Class<?> type, String component)
            throws NoSuchFieldException {
        return type.getDeclaredField(component).getAnnotation(Description.class);
    }

    private static String excerptAround(String text, String token) {
        int i = text.indexOf(token);
        if (i < 0) {
            return "";
        }
        return text.substring(Math.max(0, i - 120), Math.min(text.length(), i + 120));
    }
}
