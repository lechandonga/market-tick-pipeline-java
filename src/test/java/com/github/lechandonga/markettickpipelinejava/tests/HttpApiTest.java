package com.github.lechandonga.markettickpipelinejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 业务：HTTP 接口端到端（提交行情 / 拒绝原因 / 查询聚合）。
 * 使用随机端口 + 隔离的测试数据目录（application-test.properties）。
 */
@SpringBootTest(properties = "pipeline.data-dir=target/test-data/http-it")
@AutoConfigureMockMvc
class HttpApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void submitValidThenQueryWindow() throws Exception {
        String sym = "HTTP-VALID-" + System.nanoTime();
        String tick = tick(sym, 1, 100, 3, 1000);
        mockMvc.perform(post("/api/ticks").contentType(MediaType.APPLICATION_JSON).content(tick))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.windowStart").value(0));

        mockMvc.perform(get("/api/symbols/" + sym + "/windows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].open").value(100.0))
                .andExpect(jsonPath("$[0].volume").value(3.0))
                .andExpect(jsonPath("$[0].tickCount").value(1));

        mockMvc.perform(get("/api/symbols/" + sym + "/windows/at").param("timestamp", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.high").value(100.0));
    }

    @Test
    void duplicateReturns409WithDistinctReason() throws Exception {
        String sym = "HTTP-DUP-" + System.nanoTime();
        String tick = tick(sym, 1, 100, 3, 1000);
        mockMvc.perform(post("/api/ticks").contentType(MediaType.APPLICATION_JSON).content(tick))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/ticks").contentType(MediaType.APPLICATION_JSON).content(tick))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.reason").value("DUPLICATE_SEQ"));
    }

    @Test
    void invalidTickReturns400WithDistinctReasons() throws Exception {
        String sym = "HTTP-INV-" + System.nanoTime();
        mockMvc.perform(post("/api/ticks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"" + sym + "X\",\"seq\":1,\"quantity\":1,\"timestamp\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.reason").value("MISSING_FIELD"));

        mockMvc.perform(post("/api/ticks").contentType(MediaType.APPLICATION_JSON)
                        .content(tick(sym, 1, -1, 1, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.reason").value("INVALID_PRICE"));

        mockMvc.perform(post("/api/ticks").contentType(MediaType.APPLICATION_JSON).content("not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.reason").value("MALFORMED_JSON"));
    }

    @Test
    void lateTickReturns422() throws Exception {
        String sym = "HTTP-LATE-" + System.nanoTime();
        // 默认窗口 60s、迟到 5s
        mockMvc.perform(post("/api/ticks").contentType(MediaType.APPLICATION_JSON)
                        .content(tick(sym, 1, 100, 1, 0)))
                .andExpect(status().isCreated());
        // 推进到 130000ms：watermark=125000，窗口 [0,60000) 已关闭
        mockMvc.perform(post("/api/ticks").contentType(MediaType.APPLICATION_JSON)
                        .content(tick(sym, 2, 100, 1, 130_000)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/ticks").contentType(MediaType.APPLICATION_JSON)
                        .content(tick(sym, 3, 100, 1, 1_000)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.reason").value("LATE_CLOSED_WINDOW"));
    }

    private static String tick(String symbol, long seq, double price, double qty, long ts) {
        return String.format(
                "{\"symbol\":\"%s\",\"seq\":%d,\"price\":%s,\"quantity\":%s,\"timestamp\":%d}",
                symbol, seq, price, qty, ts);
    }
}
