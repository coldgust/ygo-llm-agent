package com.github.coldgust.ygoplay;

import java.nio.file.Path;

public record ComboRecording(String duelId, String archetype, Path duelLogPath) {
}
