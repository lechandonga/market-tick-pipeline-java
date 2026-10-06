package com.github.lechandonga.markettickpipelinejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.markettickpipelinejava.model.Tick;
import com.github.lechandonga.markettickpipelinejava.storage.TickJournal;
import com.github.lechandonga.markettickpipelinejava.service.TickPipelineService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 测试辅助：构造独立数据目录的服务实例，便于并发/恢复场景验证。
 */
final class TestSupport {

    static final long WINDOW = 60_000L;

    private TestSupport() {
    }

    static Path newDataDir() throws IOException {
        return Files.createTempDirectory("tick-pipeline-test-");
    }

    static TickJournal journal(Path dir) {
        TickJournal journal = new TickJournal(dir.toString(), new ObjectMapper());
        try {
            journal.init();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return journal;
    }

    static TickPipelineService newService(Path dir) {
        TickPipelineService service = new TickPipelineService(journal(dir), WINDOW);
        service.recover();
        return service;
    }

    /**
     * 以“当前时间往前第 3 个窗口的起点”为基准，避免测试跨真实窗口边界造成抖动。
     */
    static long baseWindowStart(long windowSize) {
        long now = System.currentTimeMillis();
        return Math.floorDiv(now, windowSize) * windowSize - 3 * windowSize;
    }

    static Tick tick(String symbol, long seq, double price, double volume, long ts) {
        return new Tick(symbol, seq, price, volume, ts);
    }
}
