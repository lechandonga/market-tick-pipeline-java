package com.github.lechandonga.markettickpipelinejava.tests;

import com.github.lechandonga.markettickpipelinejava.model.SubmitOutcome;
import com.github.lechandonga.markettickpipelinejava.model.Tick;
import com.github.lechandonga.markettickpipelinejava.model.WindowAggregate;
import com.github.lechandonga.markettickpipelinejava.service.TickPipelineService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.lechandonga.markettickpipelinejava.tests.TestSupport.WINDOW;
import static com.github.lechandonga.markettickpipelinejava.tests.TestSupport.baseWindowStart;
import static com.github.lechandonga.markettickpipelinejava.tests.TestSupport.newDataDir;
import static com.github.lechandonga.markettickpipelinejava.tests.TestSupport.newService;
import static com.github.lechandonga.markettickpipelinejava.tests.TestSupport.tick;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务三：并发提交同一品种，结果必须与串行提交完全一致。
 */
@DisplayName("业务三：并发提交等价于串行提交")
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    /**
     * 串行参照结果：把同一批报文按 seq 顺序提交到独立服务，得到基准 OHLCV。
     */
    private WindowAggregate serialReference(List<Tick> ticks) throws Exception {
        TickPipelineService reference = newService(newDataDir());
        ticks.stream().sorted(java.util.Comparator.comparingLong(Tick::seq))
                .forEach(reference::submit);
        return reference.queryWindow(ticks.get(0).symbol(),
                baseWindowStart(WINDOW));
    }

    @RepeatedTest(5)
    @DisplayName("3.1 8 线程并发提交同一品种（含重复序号），OHLCV/成交量/tickCount 与串行完全一致")
    void concurrentSubmissionMatchesSerial() throws Exception {
        long t0 = baseWindowStart(WINDOW);
        List<Tick> uniqueTicks = new ArrayList<>();
        long n = 200;
        for (long seq = 1; seq <= n; seq++) {
            double price = 100 + (seq % 17);
            double volume = seq % 5;
            long ts = t0 + (seq % 50) * 1_000L;
            uniqueTicks.add(tick("CONC", seq, price, volume, ts));
        }
        WindowAggregate expected = serialReference(uniqueTicks);

        // 构造带重复的提交集：每个序号重复 2 次
        List<Tick> shuffled = new ArrayList<>();
        for (Tick t : uniqueTicks) {
            shuffled.add(t);
            // 重复报文载荷与原文完全相同：无论哪一份先到达，聚合结果都一致，
            // 因而可以与顺序无关的串行基准对比。
            shuffled.add(tick("CONC", t.seq(), t.price(), t.volume(), t.timestamp()));
        }
        Collections.shuffle(shuffled);

        Path dir = newDataDir();
        TickPipelineService service = newService(dir);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<SubmitOutcome>> futures = new ArrayList<>();
        int chunk = shuffled.size() / threads;
        for (int i = 0; i < threads; i++) {
            List<Tick> part = shuffled.subList(i * chunk,
                    i == threads - 1 ? shuffled.size() : (i + 1) * chunk);
            futures.add(pool.submit((Callable<SubmitOutcome>) () -> {
                SubmitOutcome last = null;
                for (Tick t : part) {
                    last = service.submit(t);
                }
                return last;
            }));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        for (Future<SubmitOutcome> f : futures) {
            f.get();
        }

        WindowAggregate actual = service.queryWindow("CONC", t0);
        assertEquals(expected.open(), actual.open());
        assertEquals(expected.high(), actual.high());
        assertEquals(expected.low(), actual.low());
        assertEquals(expected.close(), actual.close());
        assertEquals(expected.volume(), actual.volume(), 0.0001);
        assertEquals(expected.tickCount(), actual.tickCount());
        assertEquals(n, actual.tickCount(), "重复提交不应增加计价报文数");
        log.info("[用例] 并发 {} 线程、{} 笔唯一报文(每笔重复1次)：open={}, high={}, low={}, close={}, volume={}, tickCount={} 与串行基准一致",
                threads, n, actual.open(), actual.high(), actual.low(), actual.close(),
                actual.volume(), actual.tickCount());
    }
}
