package com.github.lechandonga.markettickpipelinejava.tests;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务五：HTTP 端到端。提交接口的接受/重复/迟到/拒绝四类语义，以及查询接口。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "pipeline.window-size-millis=60000",
                "logging.file.name=" // 测试期不落 pipeline.log 文件
        })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("业务五：HTTP 接口端到端")
class HttpApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HttpApiIntegrationTest.class);

    @Value("${pipeline.data-dir}")
    private String dataDir;

    @Value("${local.server.port}")
    private int port;

    private final TestRestTemplate rest = new TestRestTemplate();

    private static long ts(long offsetMillis) {
        return TestSupport.baseWindowStart(TestSupport.WINDOW) + offsetMillis;
    }

    @BeforeAll
    static void banner() {
        log.info("########## 业务五 HTTP 端到端测试开始 ##########");
    }

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) throws Exception {
        Path dir = Files.createTempDirectory("tick-pipeline-http-");
        registry.add("pipeline.data-dir", () -> dir.toString());
        log.info("[测试数据目录] {}", dir);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @Test
    @Order(1)
    @DisplayName("5.1 接受正常报文并查询到 OHLCV")
    void acceptAndQuery() {
        String body = "{\"symbol\":\"HTTP\",\"seq\":1,\"price\":100,\"volume\":10,\"timestamp\":"
                + ts(1_000) + "}";
        ResponseEntity<String> resp = rest.postForEntity(url("/api/ticks"), json(body), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().contains("ACCEPTED"));

        ResponseEntity<String> windows = rest.getForEntity(
                url("/api/ticks/HTTP/windows"), String.class);
        assertEquals(HttpStatus.OK, windows.getStatusCode());
        assertTrue(windows.getBody().contains("\"open\":100.0"));
        assertTrue(windows.getBody().contains("\"volume\":10.0"));
        log.info("[用例] POST /api/ticks 正常报文 -> ACCEPTED；GET 窗口聚合 open=100 volume=10 成功");
    }

    @Test
    @Order(2)
    @DisplayName("5.2 缺字段报文返回 422 与可区分 reason")
    void rejectMissingField() {
        ResponseEntity<String> resp = rest.postForEntity(url("/api/ticks"),
                json("{\"symbol\":\"HTTP\",\"seq\":2,\"price\":100,\"volume\":1}"), String.class);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode());
        assertTrue(resp.getBody().contains("MISSING_TIMESTAMP"));
        log.info("[用例] 缺 timestamp -> HTTP 422 reason=MISSING_TIMESTAMP，未静默丢弃");
    }

    @Test
    @Order(3)
    @DisplayName("5.3 非法 JSON 返回 400 MALFORMED_JSON")
    void rejectMalformed() {
        ResponseEntity<String> resp = rest.postForEntity(url("/api/ticks"),
                json("not-a-json"), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().contains("MALFORMED_JSON"));
        log.info("[用例] 非法 JSON -> HTTP 400 reason=MALFORMED_JSON");
    }

    @Test
    @Order(4)
    @DisplayName("5.4 重复序号返回 200 DUPLICATE；迟到报文返回 200 LATE_DROPPED")
    void duplicateAndLateOverHttp() {
        String dup = "{\"symbol\":\"HTTP\",\"seq\":1,\"price\":100,\"volume\":10,\"timestamp\":"
                + ts(1_000) + "}";
        ResponseEntity<String> dupResp = rest.postForEntity(url("/api/ticks"), json(dup), String.class);
        assertEquals(HttpStatus.OK, dupResp.getStatusCode());
        assertTrue(dupResp.getBody().contains("DUPLICATE"));

        // 先用后续窗口报文关闭当前窗口，再迟到提交
        String closer = "{\"symbol\":\"HTTP\",\"seq\":9,\"price\":200,\"volume\":5,\"timestamp\":"
                + ts(TestSupport.WINDOW + 1000) + "}";
        rest.postForEntity(url("/api/ticks"), json(closer), String.class);
        String late = "{\"symbol\":\"HTTP\",\"seq\":10,\"price\":1,\"volume\":500,\"timestamp\":"
                + ts(2_000) + "}";
        ResponseEntity<String> lateResp = rest.postForEntity(url("/api/ticks"), json(late), String.class);
        assertEquals(HttpStatus.OK, lateResp.getStatusCode());
        assertTrue(lateResp.getBody().contains("LATE_DROPPED"));
        assertTrue(lateResp.getBody().contains("WINDOW_CLOSED_LATE"));
        log.info("[用例] HTTP 重复提交 -> DUPLICATE；关闭窗口后迟到 -> LATE_DROPPED/WINDOW_CLOSED_LATE");

        ResponseEntity<String> all = rest.getForEntity(url("/api/ticks/HTTP/windows"), String.class);
        assertNotNull(all.getBody());
        assertTrue(all.getBody().contains("\"windowSizeMillis\":60000"));
    }

    @Test
    @Order(5)
    @DisplayName("5.5 原始报文确实落到本地文件")
    void journalExistsLocally() {
        Path journal = Path.of(dataDir, "tick-journal.log");
        assertTrue(Files.exists(journal), "本地日志文件应存在: " + journal);
        log.info("[用例] 本地原始报文日志存在: {}", journal.toAbsolutePath());
    }
}
