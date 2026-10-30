package com.github.coldgust.ygoplayserver.duel.summary;

import com.github.coldgust.ygoagent.decision.planexec.llm.ChatClient;
import com.github.coldgust.ygoagent.decision.planexec.llm.LlmTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 一次赛后复盘调用。
 *
 * <p>刻意做得"能失败"：返回 {@link Optional#empty()} 表示这次没拿到可用复盘，
 * 调用方退回确定性模板。摘要属于旁路产物，绝不能因为它挂掉而对局结果受影响。
 *
 * <p>除 7 个固定小节外，模型还要给「入库判定」小节（这局有没有值得沉淀的经验）。
 * 判定与正文一起返回：正文已剥离判定节（元信息不进对局库），判定供 auto 入库策略裁决。
 */
public final class DuelSummarizer {

    private static final Logger log = LoggerFactory.getLogger(DuelSummarizer.class);

    /** 模型写在「入库判定」小节里的否定标记（肯定标记缺失视同值得，见 {@link #worth}）。 */
    static final String NOT_WORTH_MARK = "判定：不值得入库";
    private static final String VERDICT_HEADING = "## 入库判定";

    /** 复盘正文（已剥掉入库判定节）+ 模型判定。 */
    public record Review(String body, boolean worthStoring) {
    }

    private final ChatClient chat;
    private final int maxTokens;
    private final Duration timeout;

    public DuelSummarizer(ChatClient chat, int maxTokens, Duration timeout) {
        this.chat = chat;
        this.maxTokens = Math.max(500, maxTokens);
        this.timeout = timeout;
    }

    public Optional<Review> summarize(String facts, String timeline) {
        String prompt = DuelSummaryPrompts.user(facts, timeline);
        try {
            // 作用域标签让日志能分清"这次请求是赛后复盘还是对局决策"
            String raw;
            try (var ignored = LlmTrace.scope("赛后复盘")) {
                raw = chat.complete(
                        List.of(ChatClient.Msg.system(DuelSummaryPrompts.SYSTEM),
                                ChatClient.Msg.user(prompt)),
                        ChatClient.ChatOptions.narrative(maxTokens, timeout));
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

    /**
     * 解析「入库判定」小节。
     *
     * <p>按第一行标记「判定：不值得入库」判否（不能模糊查"不值得"，理由里可能出现这个字样）。
     * 判定缺失或格式不符时按值得处理——模型已经写出完整复盘，宁可多收一条，也不因漏一行就丢。
     */
    static boolean worth(String body) {
        String verdict = section(body, VERDICT_HEADING);
        return !verdict.contains(NOT_WORTH_MARK);
    }

    /** 剥掉入库判定节（含节标题到下一个 ## 之前）；没有该节则原样返回。 */
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

    /** 取一个小节的正文（标题行之后到下一个 ## 之前）。 */
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
