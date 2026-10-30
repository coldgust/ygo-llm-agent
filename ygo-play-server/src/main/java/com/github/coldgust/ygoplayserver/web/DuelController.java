package com.github.coldgust.ygoplayserver.web;

import com.github.coldgust.ygoplayserver.duel.ComboCatalog;
import com.github.coldgust.ygoplayserver.duel.DuelExceptions;
import com.github.coldgust.ygoplayserver.duel.DuelRequest;
import com.github.coldgust.ygoplayserver.duel.DuelService;
import com.github.coldgust.ygoplayserver.duel.DuelView;
import com.github.coldgust.ygoplayserver.duel.combo.ComboRecordService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.nio.file.Path;
import java.util.Map;

/** 对局 HTTP 接口：启动 / 查询 / 停止。 */
@RestController
@RequestMapping("/api/duels")
public class DuelController {

    private final DuelService duels;
    private final DuelEventHub eventHub;
    private final ObjectProvider<JdbcClient> jdbc;
    private final ObjectProvider<ComboRecordService> comboRecords;

    /** 测试/轻量场景便利构造器。 */
    public DuelController(DuelService duels) {
        this(duels, new DuelEventHub(), null, null);
    }

    @Autowired
    public DuelController(DuelService duels, DuelEventHub eventHub,
                          ObjectProvider<JdbcClient> jdbc,
                          ObjectProvider<ComboRecordService> comboRecords) {
        this.duels = duels;
        this.eventHub = eventHub;
        this.jdbc = jdbc;
        this.comboRecords = comboRecords;
    }

    /** 实时决策/规划器/对局状态事件流（SSE）。对局终结后服务端主动 complete。 */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) {
        duels.get(id); // 不存在则 404
        return eventHub.register(duels.traceId(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DuelView start(@RequestBody DuelRequest request) {
        return duels.start(request);
    }

    /** 前端"卡组主题"下拉：种子编译目录 ∪ PG 实战录入的主题（空 = 库里没有该主题的 combo）。 */
    @GetMapping("/archetypes")
    public List<String> archetypes() {
        return ComboCatalog.archetypes(jdbc == null ? null : jdbc.getIfAvailable());
    }

    @GetMapping
    public List<DuelView> list() {
        return duels.list();
    }

    @GetMapping("/{id}")
    public DuelView get(@PathVariable String id) {
        return duels.get(id);
    }

    @PostMapping("/{id}/stop")
    public DuelView stop(@PathVariable String id) {
        return duels.stop(id);
    }

    /** 「结束后询问」模式下用户对入库的决定；body 为 {"store": true|false}。 */
    @PostMapping("/{id}/archive")
    public DuelView archive(@PathVariable String id, @RequestBody ArchiveDecision body) {
        return duels.archiveDecision(id, Boolean.TRUE.equals(body.store()));
    }

    /**
     * 某局对局的 combo 录入结果（HTTP 兜底：SSE 的 COMBO_RECORDED 没收到时轮询它）。
     * 无记录返回 404，前端按"未完成录入"处理。
     */
    @GetMapping("/{id}/combo")
    public Map<String, Object> comboRecord(@PathVariable String id) {
        DuelView view = duels.get(id);
        ComboRecordService service = comboRecords == null ? null : comboRecords.getIfAvailable();
        if (service == null) {
            throw new DuelExceptions.NotFoundException("combo 录入服务不可用");
        }
        String duelKey = Path.of(view.duelLogPath()).getFileName().toString()
                .replaceFirst("\\.jsonl$", "");
        return service.findLatest(duelKey)
                .orElseThrow(() -> new DuelExceptions.NotFoundException("该对局没有 combo 录入记录"));
    }

    /** 入库决定的请求体。store 缺省按 false（不入库）处理。 */
    public record ArchiveDecision(Boolean store) {
    }

    @DeleteMapping("/{id}")
    public DuelView delete(@PathVariable String id) {
        return duels.stop(id);
    }
}
