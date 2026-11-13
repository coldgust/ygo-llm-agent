package com.github.coldgust.ygoplayserver.duel.summary;

import com.github.coldgust.ygoagent.llm.service.NarratorAgent;
import com.github.coldgust.ygoagent.llm.LlmEndpoint;
import com.github.coldgust.ygoagent.llm.VerboseLog;
import com.github.coldgust.ygoagent.llm.VerboseLogSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public final class DuelSummarizer {

    private static final Logger log = LoggerFactory.getLogger(DuelSummarizer.class);

    static final String NOT_WORTH_MARK = "判定：不值得入库";
    private static final String VERDICT_HEADING = "## 入库判定";

    public record Review(String body, boolean worthStoring) {
    }

    private final NarratorAgent narratorAgent;
    private final VerboseLog trace;

    public DuelSummarizer(NarratorAgent narratorAgent, VerboseLog trace) {
        this.narratorAgent = narratorAgent;
        this.trace = trace == null ? new VerboseLog(VerboseLogSettings.off()) : trace;
    }

    public Optional<Review> summarize(String facts, String timeline) {
        String prompt = DuelSummaryPrompts.user(facts, timeline);
        try {
            // 作用域标签让日志能分清"这次请求是赛后复盘还是对局决策"
            String raw;
            try (var ignored = trace.scope("赛后复盘")) {
                raw = narratorAgent.write(prompt);
            }
            String body = DuelSummaryPrompts.stripWrapper(raw);
            if (!DuelSummaryPrompts.looksUsable(body)) {
                log.warn("复盘输出不可用（缺小节或过短，{} 字）：{}", body.length(),
                        body.substring(0, Math.min(120, body.length())));
                return Optional.empty();
            }
            return Optional.of(new Review(stripVerdict(body), worth(body)));
        } catch (RuntimeException e) {
            log.warn("复盘调用失败：{}", e.toString());
            return Optional.empty();
        }
    }

    static boolean worth(String body) {
        String verdict = section(body, VERDICT_HEADING);
        return !verdict.contains(NOT_WORTH_MARK);
    }

    static String stripVerdict(String body) {
        int start = body.indexOf(VERDICT_HEADING);
        if (start < 0) {
            return body.trim();
        }
        String head = body.substring(0, start).trim();
        String rest = body.substring(start + VERDICT_HEADING.length());
        int next = rest.indexOf("\n## ");
        String tail = next >= 0 ? "\n\n" + rest.substring(next).trim() : "";
        return (head + tail).trim();
    }

    private static String section(String body, String heading) {
        int start = body.indexOf(heading);
        if (start < 0) {
            return "";
        }
        String rest = body.substring(start + heading.length());
        int next = rest.indexOf("\n## ");
        return next >= 0 ? rest.substring(0, next) : rest;
    }
}
