package com.github.coldgust.ygoplay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 特征测试：`.env` 解析语义（dotenv-java 承接自研解析的契约基线）。
 * load 返回"OS env 优先、.env 兜底"的合并表（dotenv 内置），混入全部 OS env 变量——
 * 断言一律针对文件声明的键，不 pin 表的总大小。
 */
class DotEnvTest {

    @TempDir
    Path tmp;

    @Test
    void loadParsesEntriesAndStripsQuotes() throws Exception {
        Path file = write(".env", """
                PLAIN=value
                DOUBLE="带 空格"
                SINGLE='单引号'
                """);
        Map<String, String> values = DotEnv.load(file);
        assertEquals("value", values.get("PLAIN"));
        assertEquals("带 空格", values.get("DOUBLE"));
        assertEquals("单引号", values.get("SINGLE"));
    }

    @Test
    void commentsBlankLinesAndEntriesWithoutEqualsAreSkipped() throws Exception {
        Path file = write(".env", """
                # 整行注释
                KEY=1

                BAD_LINE_WITHOUT_EQUALS
                NEXT=2
                """);
        Map<String, String> values = DotEnv.load(file);
        assertEquals("1", values.get("KEY"));
        assertEquals("2", values.get("NEXT"));
        assertNull(values.get("YGO_DOTENV_TEST_ABSENT"), "文件未声明的键不应凭空出现");
    }

    /** 行内注释剥离（dotenv-java 的行为改进）：.env.example 的 `KEY=value # 说明` 不再把注释读进值。 */
    @Test
    void inlineCommentsAreStrippedFromUnquotedValues() throws Exception {
        Path file = write(".env", """
                PLANNER_WAIT_DECISION_MS=4000     # 决策时一次最多等待规划器
                URL=https://api.deepseek.com      # 地址
                QUOTED="4000 # 不是注释"
                """);
        Map<String, String> values = DotEnv.load(file);
        assertEquals("4000", values.get("PLANNER_WAIT_DECISION_MS"));
        assertEquals("https://api.deepseek.com", values.get("URL"));
        assertEquals("4000 # 不是注释", values.get("QUOTED"));
    }

    @Test
    void missingFileYieldsEmptyMap() {
        assertTrue(DotEnv.load(tmp.resolve("nope.env")).isEmpty());
    }

    /** 真实契约：仓库 .env.example 的行内注释要被剥掉，URL 里的 // 不能被当注释；调优键已注释化（默认在代码）。 */
    @Test
    void realEnvExampleStripsInlineCommentsButKeepsUrlSlashes() throws Exception {
        Path example = Path.of("../.env.example");
        if (!Files.isRegularFile(example)) {
            return; // 文件不在时跳过（仓库内恒存在）
        }
        Map<String, String> values = DotEnv.load(example);
        assertEquals("https://api.deepseek.com", values.get("LLM_BASE_URL"));
        assertEquals("data/card_rag/cards.jsonl", values.get("CARD_RAG_PATH"));
        assertTrue(values.get("POSTGRES_URL").startsWith("jdbc:postgresql://"));
        assertNull(values.get("PLANNER_WAIT_DECISION_MS"), "调优键已注释化：默认在代码，不再由 .env.example 承载");
    }

    private Path write(String name, String content) throws Exception {
        Path file = tmp.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
