package com.github.coldgust.ygoagent.pipeline.advisor;

import dev.langchain4j.model.output.structured.Description;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规划器输出契约钉子（2026-11-14 用户指令：所有提示词的输出契约用 LangChain4j 注解实现，
 * 不写在提示词里）——契约唯一源 = {@link AdvisorAnswer}（含嵌套 {@link AdvisorAnswer.OptionDraft}）
 * 组件上的 {@code @Description} 注解：框架从注解生成格式指令追加到每条 checkpoint 请求，
 * system 只保留行为纪律与前后一致性要求。本钉子防止"组件没写注解 → 语义悄悄丢失"。
 * keep 已废止（2026-11-14 用户指令）：POJO 只有全量建议一种输出形态。
 */
class AdvisorAnswerContractTest {

    @Test
    void everyComponentHasAnAnnotationContract() throws Exception {
        // @Description 的 @Target 是 FIELD/TYPE（不含 RECORD_COMPONENT）：注解落在后备字段上，
        // 反射按字段读（与框架 JsonSchemaElementUtils 的读取路径一致）。
        for (RecordComponent component : AdvisorAnswer.class.getRecordComponents()) {
            Description description = annotationOf(AdvisorAnswer.class, component.getName());
            assertNotNull(description, "组件缺少 @Description 契约: " + component.getName());
            assertTrue(String.join("", description.value()).length() >= 8,
                    "@Description 不应为空泛占位: " + component.getName());
        }
        for (RecordComponent component : AdvisorAnswer.OptionDraft.class.getRecordComponents()) {
            Description description = annotationOf(AdvisorAnswer.OptionDraft.class,
                    component.getName());
            assertNotNull(description, "嵌套组件缺少 @Description 契约: " + component.getName());
        }
    }

    @Test
    void lengthGuardsSurviveTheAnnotationMigration() throws Exception {
        // 字数上限是截断防护（fastPlan 额度实测），迁移到注解后不能丢
        for (String field : List.of("strategy", "analysis", "resources", "tactics", "intel", "combo")) {
            String description = descriptionOf(AdvisorAnswer.class, field);
            assertTrue(description.contains("字"), "字段缺字数上限: " + field);
        }
    }

    @Test
    void optionPickExactlyOneContract() throws Exception {
        String options = descriptionOf(AdvisorAnswer.class, "options");
        assertTrue(options.contains("pick=true"), "options 的注解应钉住恰好一条 pick=true");
        String pick = descriptionOf(AdvisorAnswer.OptionDraft.class, "pick");
        assertTrue(pick.contains("true"), "OptionDraft.pick 的注解应钉住唯一 true");
    }

    @Test
    void carriesNineComponentsAndNoKeep() throws Exception {
        assertEquals(9, AdvisorAnswer.class.getRecordComponents().length,
                Arrays.stream(AdvisorAnswer.class.getRecordComponents())
                        .map(RecordComponent::getName).collect(Collectors.joining("/")));
        // keep 已随回合内主动修订废止：POJO 不得再出现该键（模型回 keep 形态 → 无内容 → 解析失败）
        assertTrue(Arrays.stream(AdvisorAnswer.class.getDeclaredFields())
                        .noneMatch(f -> f.getName().equals("keep")),
                "keep 字段应已从 POJO 移除");
    }

    private static String descriptionOf(Class<?> type, String component) throws Exception {
        return String.join("", annotationOf(type, component).value());
    }

    /** @Description 只能标在字段上（@Target 无 RECORD_COMPONENT），从后备字段读。 */
    private static Description annotationOf(Class<?> type, String component)
            throws NoSuchFieldException {
        return type.getDeclaredField(component).getAnnotation(Description.class);
    }
}
