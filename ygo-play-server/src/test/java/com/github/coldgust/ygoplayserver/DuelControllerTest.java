package com.github.coldgust.ygoplayserver;

import com.github.coldgust.ygoplayserver.duel.DuelExceptions;
import com.github.coldgust.ygoplayserver.duel.DuelRequest;
import com.github.coldgust.ygoplayserver.duel.DuelService;
import com.github.coldgust.ygoplayserver.duel.DuelView;
import com.github.coldgust.ygoplayserver.web.ApiExceptionHandler;
import com.github.coldgust.ygoplayserver.web.DuelController;
import com.github.coldgust.ygoplayserver.web.DuelEventHub;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DuelControllerTest {

    @Test
    void startReturnsAccepted() throws Exception {
        MockMvc mvc = mockMvc(new FakeService());
        mvc.perform(post("/api/duels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"host\":\"127.0.0.1\",\"port\":7911,\"deckYdk\":\"main:1\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("id-1"))
                .andExpect(jsonPath("$.status").value("STARTING"));
    }

    @Test
    void validationAndNotFoundMapped() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DuelController(new FailingService()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        mvc.perform(post("/api/duels").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad deck"));
        mvc.perform(get("/api/duels/x"))
                .andExpect(status().isNotFound());
    }

    private static MockMvc mockMvc(DuelService service) {
        return MockMvcBuilders.standaloneSetup(new DuelController(service, new DuelEventHub(), null, null))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static DuelView view(String status) {
        return new DuelView("id-1", status, "127.0.0.1", 7911, "n", "second", "inline", null,
                Instant.now(), null, null, null, null, null, null,
                "/tmp/t.jsonl", "/tmp/d.jsonl", "ask", "PENDING");
    }

    private static class FakeService implements DuelService {
        @Override
        public DuelView start(DuelRequest request) {
            return view("STARTING");
        }

        @Override
        public String traceId(String id) {
            return "trace-" + id;
        }

        public DuelView get(String id) {
            return view("RUNNING");
        }

        @Override
        public List<DuelView> list() {
            return List.of();
        }

        @Override
        public DuelView stop(String id) {
            return view("STOPPED");
        }

        @Override
        public DuelView archiveDecision(String id, boolean store) {
            return view("FINISHED");
        }
    }

    private static class FailingService extends FakeService {
        @Override
        public DuelView start(DuelRequest request) {
            throw new DuelExceptions.ValidationException("bad deck");
        }

        @Override
        public String traceId(String id) {
            return "trace-" + id;
        }

        public DuelView get(String id) {
            throw new DuelExceptions.NotFoundException("missing");
        }
    }
}
