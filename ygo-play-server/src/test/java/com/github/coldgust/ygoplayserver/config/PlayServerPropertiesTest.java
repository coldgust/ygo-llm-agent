package com.github.coldgust.ygoplayserver.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ygo.play 绑定（键用全限定名）：非 String 组件的空串/缺失绑定为 null = 继承代码默认（RunnerConfig.defaults 等）；
 * 环境相关项（host 等地址/路径）缺失 → compact 校验抛异常点名 = 启动期失败。 */
class PlayServerPropertiesTest {

    private static Map<String, String> fullValues() {
        Map<String, String> values = new HashMap<>();
        values.put("ygo.play.host", "127.0.0.1");
        values.put("ygo.play.port", "7911");
        values.put("ygo.play.player-name", "p");
        values.put("ygo.play.default-move", "second");
        values.put("ygo.play.max-concurrent-duels", "1");
        values.put("ygo.play.duel-log-dir", "/tmp/ygo-duel-log");
        values.put("ygo.play.trace-dir", "/tmp/ygo-trace");
        values.put("ygo.play.duel-log-raw-frames", "");
        values.put("ygo.play.choice-timeout-ms", "");
        values.put("ygo.play.start-wait-ms", "");
        values.put("ygo.play.combo-context-limit", "");
        values.put("ygo.play.runner-poll-ms", "");
        values.put("ygo.play.runner-default-budget-ms", "");
        return values;
    }

    @Test
    void emptyTuningBridgeBindsToNullThenCompactDefaults() {
        PlayServerProperties props = new Binder(new MapConfigurationPropertySource(fullValues()))
                .bind("ygo.play", Bindable.of(PlayServerProperties.class)).get();
        assertNotNull(props);
        assertEquals(25_000L, props.choiceTimeoutMs(), "空串绑定 null → compact 引用 DuelTuning.defaults");
        assertEquals(180_000L, props.startWaitMs());
        assertEquals(10, props.comboContextLimit());
        assertFalse(props.duelLogRawFrames());
        assertNull(props.runnerPollMs(), "runner 桥接未配置：字段 null = RunnerConfig.defaults 兜底");
    }

    @Test
    void missingEnvRelatedKeyFailsAtStartup() {
        Map<String, String> values = fullValues();
        values.remove("ygo.play.host");
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(values);
        // host 是环境相关必填：桥接缺失 → 绑定为 null → compact 校验抛出点名（启动期失败）
        assertThrows(Exception.class,
                () -> new Binder(source).bind("ygo.play", Bindable.of(PlayServerProperties.class)).get(),
                "环境相关必填缺失必须启动期失败");
    }

    @Test
    void hostMustNotBeBlank() {
        Map<String, String> values = fullValues();
        values.put("ygo.play.host", "");
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(values);
        assertThrows(Exception.class,
                () -> new Binder(source).bind("ygo.play", Bindable.of(PlayServerProperties.class)).get());
    }
}
