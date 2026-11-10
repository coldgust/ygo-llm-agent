package com.github.coldgust.ygoduellog;

import com.github.coldgust.ygoduellog.event.DuelEvent;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class JsonlSink implements DuelEventSink {

    /** FAIL_ON_EMPTY_BEANS 必须关闭：CardIdentity.Unknown 等零字段 record 是合法载荷。 */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .build();

    private final BufferedWriter writer;

    public JsonlSink(Path file) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建对局记录文件: " + file, e);
        }
    }

    @Override
    public void write(DuelEvent event) {
        try {
            writer.write(MAPPER.writeValueAsString(event));
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("对局记录写入失败", e);
        }
    }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException ignored) {
        }
    }
}
