package com.github.lechandonga.markettickpipelinejava.tests.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.lechandonga.markettickpipelinejava.service.AggregationService;
import com.github.lechandonga.markettickpipelinejava.store.TickLogStore;
import com.github.lechandonga.markettickpipelinejava.validation.TickValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * 测试辅助：在独立临时目录上组装一套与生产相同结构的服务，
 * 并提供行情 JSON 构造便捷方法。窗口/迟到参数可按需定制。
 */
public final class PipelineTestSupport {

    public final Path dataDir;
    public final TickLogStore logStore;
    public final AggregationService service;
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
            .build();

    private PipelineTestSupport(Path dataDir, long windowMs, long latenessMs) {
        this.dataDir = dataDir;
        this.logStore = new TickLogStore(dataDir.toString(), objectMapper);
        try {
            this.logStore.init();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.service = new AggregationService(
                new TickValidator(objectMapper), this.logStore, windowMs, latenessMs);
    }

    public static PipelineTestSupport create(long windowMs, long latenessMs) throws IOException {
        Path dir = Files.createTempDirectory("pipeline-test-");
        return new PipelineTestSupport(dir, windowMs, latenessMs);
    }

    /** 基于同一数据目录重建服务（模拟进程重启，内存清空、日志保留）。 */
    public PipelineTestSupport restart() throws IOException {
        return new PipelineTestSupport(dataDir,
                service.windowSizeMs(), service.allowedLatenessMs());
    }

    public void replayAll() throws IOException {
        for (TickLogStore.RecoveredSymbol rs : logStore.recoverAll()) {
            rs.ticks().forEach(service::replay);
        }
    }

    public String tickJson(String symbol, long seq, double price, double qty, long ts) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "symbol", symbol,
                    "seq", seq,
                    "price", price,
                    "quantity", qty,
                    "timestamp", ts));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public long rejectedAuditLines() throws IOException {
        Path rejected = dataDir.resolve("rejected.jsonl");
        if (!Files.exists(rejected)) {
            return 0;
        }
        return Files.readAllLines(rejected).stream().filter(l -> !l.isBlank()).count();
    }

    public void deleteDir() throws IOException {
        if (!Files.exists(dataDir)) {
            return;
        }
        try (var paths = Files.walk(dataDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
