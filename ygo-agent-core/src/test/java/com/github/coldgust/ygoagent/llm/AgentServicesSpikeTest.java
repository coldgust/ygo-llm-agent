package com.github.coldgust.ygoagent.llm;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构化输出 / AiServices 行为钉子（fake ChatModel → 真 AiServices 代理）。
 * 钉住：POJO 解析与格式指令注入、{{var}} 模板渲染（JSON 花括号示例共存）、
 * 自定义 ChatMemory 的注入语义、工具回路与轮次上限。换框架版本时先重跑这组。
 */
class AgentServicesSpikeTest {

    /** 内部用 Jackson 2 注解（langchain4j 的 POJO 解析基于 Jackson 2 databind）。 */
    record Answer(String choice, Double confidence,
                  @JsonProperty("main_line") @JsonAlias("mainLine") MainLine mainLine) {
    }

    record MainLine(List<String> steps) {
    }

    interface Assistant {
        @UserMessage("{{prompt}}\n\n输出示例（花括号是字面量）: {\"choice\": \"C1\"}")
        Answer ask(@V("prompt") String prompt, ChatRequestParameters params);

        @UserMessage("{{prompt}}")
        Answer askPlain(@V("prompt") String prompt, ChatRequestParameters params);
    }

    interface Memorized {
        @SystemMessage("固定系统提示")
        @UserMessage("{{prompt}}")
        String chat(@MemoryId String id, @V("prompt") String prompt);
    }

    private static final String SYS_MSG = "固定系统提示";

    static class FakeModel implements ChatModel {
        final List<ChatRequest> requests = new ArrayList<>();
        private final Queue<Object> replies = new ArrayDeque<>();

        void enqueue(String text) {
            replies.add(AiMessage.from(text));
        }

        void enqueueToolCall(String id, String name, String args) {
            replies.add(new AiMessage(List.of(ToolExecutionRequest.builder()
                    .id(id).name(name).arguments(args).build())));
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            requests.add(request);
            Object next = replies.poll();
            if (next instanceof AiMessage ai) {
                return ChatResponse.builder().aiMessage(ai).build();
            }
            return ChatResponse.builder().aiMessage(AiMessage.from("（无脚本回复）")).build();
        }
    }

    private static ChatRequestParameters params() {
        return ChatRequestParameters.builder().build();
    }

    @Test
    void 结构化输出_POJO解析与格式指令追加到最后一条user消息() {
        FakeModel model = new FakeModel();
        model.enqueue("{\"choice\": \"C2\", \"confidence\": 0.7, \"main_line\": {\"steps\": [\"a\"]}}");
        Assistant assistant = AiServices.create(Assistant.class, model);

        Answer answer = assistant.ask("你好", params());

        assertEquals("C2", answer.choice());
        assertEquals(0.7, answer.confidence());
        assertNotNull(answer.mainLine());
        assertEquals(List.of("a"), answer.mainLine().steps());
        // 模板里的单个 { 字面量原样保留，{{prompt}} 被替换
        String lastUser = lastUserText(model);
        assertTrue(lastUser.startsWith("你好"), lastUser);
        assertTrue(lastUser.contains("{\"choice\": \"C1\"}"), "字面量花括号不被误替换");
        // 格式指令（由 POJO schema 生成）追加在同一条 user 消息尾部
        assertTrue(lastUser.contains("choice"), "格式指令应描述 POJO 字段: " + lastUser);
    }

    @Test
    void 结构化输出_容忍markdown_fence包裹() {
        FakeModel model = new FakeModel();
        model.enqueue("```json\n{\"choice\": \"C3\", \"confidence\": 0.5}\n```");
        Assistant assistant = AiServices.create(Assistant.class, model);

        assertEquals("C3", assistant.askPlain("p", params()).choice());
    }

    @Test
    void 结构化输出_畸形JSON抛RuntimeException_调用异常可区分() {
        FakeModel model = new FakeModel();
        model.enqueue("{\"choice\": \"C");  // 截断
        Assistant assistant = AiServices.create(Assistant.class, model);

        RuntimeException parseFailure = assertThrows(RuntimeException.class,
                () -> assistant.askPlain("p", params()));
        assertTrue(!(parseFailure instanceof LlmChatException),
                "解析失败不是 LlmChatException（异常分类的依据）: " + parseFailure.getClass().getName());

        // 调用失败路径：模型抛出 → 代理原样（或包裹）传播
        FakeModel failing = new FakeModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new LlmChatException("LLM HTTP 500: boom");
            }
        };
        Assistant failingAssistant = AiServices.create(Assistant.class, failing);
        RuntimeException callFailure = assertThrows(RuntimeException.class,
                () -> failingAssistant.askPlain("p", params()));
        assertTrue(hasCauseOfType(callFailure, LlmChatException.class),
                "调用失败应能从异常链找到 LlmChatException: " + callFailure.getClass().getName());
    }

    @Test
    void 自定义ChatMemory_system经builder注入并进入memory_工具对完整存入() {
        RecordingMemory memory = new RecordingMemory();
        FakeModel model = new FakeModel();
        model.enqueue("第一答");
        model.enqueueToolCall("call-1", "echo_tool", "{\"text\":\"x\"}");
        model.enqueue("第二答");

        AssistantWithTools assistant = AiServices.builder(AssistantWithTools.class)
                .chatModel(model)
                .systemMessage("固定系统提示")
                .chatMemory(memory)
                .tools(new EchoTool())
                .maxToolCallingRoundTrips(4)
                .build();

        assertEquals("第一答", assistant.chat("duel-1", "问1", params()));
        assertEquals("第二答", assistant.chat("duel-1", "问2", params()));

        // system 消息进入自定义 memory（add 收到 SystemMessage），且请求里 system 在最前
        assertTrue(memory.added.stream().anyMatch(m -> m instanceof dev.langchain4j.data.message.SystemMessage),
                "system 应通过 memory.add 注入: " + memory.added);
        ChatRequest last = model.requests.getLast();
        assertEquals(SYS_MSG, ((dev.langchain4j.data.message.SystemMessage) last.messages().getFirst()).text());
        // 工具对完整存入 memory：AiMessage(toolExecutionRequests) + ToolExecutionResultMessage
        assertTrue(memory.added.stream().anyMatch(m -> m instanceof dev.langchain4j.data.message.AiMessage a
                        && a.toolExecutionRequests() != null && !a.toolExecutionRequests().isEmpty()),
                "工具调用 AiMessage 应存入 memory");
        assertTrue(memory.added.stream().anyMatch(m -> m instanceof ToolExecutionResultMessage),
                "工具结果应存入 memory");
    }

    @Test
    void 轮次超限_抛异常终止调用() {
        FakeModel model = new FakeModel();
        for (int i = 0; i < 5; i++) {
            model.enqueueToolCall("call-" + i, "echo_tool", "{}");
        }
        AssistantWithTools assistant = AiServices.builder(AssistantWithTools.class)
                .chatModel(model)
                .chatMemory(new RecordingMemory())
                .tools(new EchoTool())
                .maxToolCallingRoundTrips(2)
                .build();

        assertThrows(RuntimeException.class, () -> assistant.chat("d", "p", params()));
        assertTrue(model.requests.size() <= 3, "轮次上限应截断循环: " + model.requests.size());
    }

    interface AssistantWithTools {
        @UserMessage("{{prompt}}")
        String chat(@MemoryId String id, @V("prompt") String prompt, ChatRequestParameters params);
    }

    static final class EchoTool {
        @Tool(name = "echo_tool", value = "回显文本，测试用")
        public String echo(@P(value = "要回显的文本") String text) {
            return "回显:" + text;
        }
    }

    /** 记录 add() 全量消息的自定义 ChatMemory（验证框架注入契约）。 */
    static final class RecordingMemory implements ChatMemory {
        final List<ChatMessage> added = new ArrayList<>();
        private final List<ChatMessage> messages = new ArrayList<>();

        @Override
        public Object id() {
            return "test";
        }

        @Override
        public void add(ChatMessage message) {
            added.add(message);
            messages.add(message);
        }

        @Override
        public List<ChatMessage> messages() {
            return List.copyOf(messages);
        }

        @Override
        public void clear() {
            messages.clear();
        }
    }

    private static String lastUserText(FakeModel model) {
        List<ChatMessage> messages = model.requests.getLast().messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof dev.langchain4j.data.message.UserMessage u) {
                return u.singleText();
            }
        }
        return "";
    }

    private static boolean hasCauseOfType(Throwable throwable, Class<? extends Throwable> type) {
        Throwable t = throwable;
        while (t != null) {
            if (type.isInstance(t)) {
                return true;
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return false;
    }
}
