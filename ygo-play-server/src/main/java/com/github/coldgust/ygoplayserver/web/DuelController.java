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

@RestController
@RequestMapping("/api/duels")
public class DuelController {

    private final DuelService duels;
    private final DuelEventHub eventHub;
    private final ObjectProvider<JdbcClient> jdbc;
    private final ObjectProvider<ComboRecordService> comboRecords;

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

    @PostMapping("/{id}/archive")
    public DuelView archive(@PathVariable String id, @RequestBody ArchiveDecision body) {
        return duels.archiveDecision(id, Boolean.TRUE.equals(body.store()));
    }

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

    public record ArchiveDecision(Boolean store) {
    }

    @DeleteMapping("/{id}")
    public DuelView delete(@PathVariable String id) {
        return duels.stop(id);
    }
}
