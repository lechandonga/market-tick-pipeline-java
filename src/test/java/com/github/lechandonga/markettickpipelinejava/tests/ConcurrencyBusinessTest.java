package com.github.lechandonga.markettickpipelinejava.tests;

import com.github.lechandonga.markettickpipelinejava.model.IngestResult;
import com.github.lechandonga.markettickpipelinejava.model.Tick;
import com.github.lechandonga.markettickpipelinejava.model.WindowAggregate;
import com.github.lechandonga.markettickpipelinejava.tests.support.BusinessLogExtension;
import com.github.lechandonga.markettickpipelinejava.tests.support.PipelineTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务：并发提交的正确性。
 *
 * <p>多线程并发提交同一品种，最终聚合必须与把同一批数据串行提交完全一致
 * （成交量、笔数、按业务时间戳决定的 OHLC 与到达顺序无关）；
 * 同序号报文在并发下也只能有一个线程提交成功。
 */
@ExtendWith(BusinessLogExtension.class)
class ConcurrencyBusinessTest {

    private static final Logger LOG = LoggerFactory.getLogger(ConcurrencyBusinessTest.class);
    private static PipelineTestSupport support;

    @BeforeAll
    static void setUp() throws Exception {
        // 大窗口 + 大迟到容忍：测试窗口不被关闭，专注并发去重与聚合正确性
        support = PipelineTestSupport.create(3_600_000, 3_600_000);
    }

    @AfterAll
    static void tearDown() throws Exception {
        support.deleteDir();
    }

    @Test
    @DisplayName("8 线程并发提交同一品种 800 笔乱序报文：结果与串行参考实现逐字段一致")
    void concurrentEqualsSerial() throws Exception {
        String symbol = "CONC";
        int n = 800;
        int threads = 8;

        List<Tick> ticks = new ArrayList<>();
        for (long seq = 1; seq <= n; seq++) {
            long ts = 1_000 + seq * 10;
            ticks.add(new Tick(symbol, seq, 100 + (seq % 37), seq, ts));
        }
        List<Tick> shuffled = new ArrayList<>(ticks);
        Collections.shuffle(shuffled);

        // 串行参考：另起一套服务，按同一 shuffled 列表串行提交
        PipelineTestSupport serialRef = PipelineTestSupport.create(3_600_000, 3_600_000);
        try {
            for (Tick t : shuffled) {
                assertTrue(serialRef.service.submit(
                        serialRef.tickJson(t.symbol(), t.seq(), t.price(), t.quantity(), t.ts())
                ).accepted());
            }
            WindowAggregate expected = serialRef.service.queryWindowForTs(symbol, 1_000);

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger cursor = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger accepted = new AtomicInteger();
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        int i;
                        while ((i = cursor.getAndIncrement()) < n) {
                            Tick tick = shuffled.get(i);
                            IngestResult r = support.service.submit(support.tickJson(
                                    tick.symbol(), tick.seq(), tick.price(), tick.quantity(), tick.ts()));
                            if (r.accepted()) {
                                accepted.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
            pool.shutdown();

            assertEquals(n, accepted.get(), "800 个不同序号应全部生效一次");
            WindowAggregate actual = support.service.queryWindowForTs(symbol, 1_000);
            assertEquals(expected, actual, "并发结果必须与串行结果逐字段一致");

            double expectedVolume = ticks.stream().mapToDouble(Tick::quantity).sum();
            assertEquals(expectedVolume, actual.volume(), 1e-9);
            assertEquals(n, actual.tickCount());
            LOG.info("  -> 并发 {} 线程/{} 笔：accepted={} volume={} 与串行结果一致 ✓",
                    threads, n, accepted.get(), actual.volume());
        } finally {
            serialRef.deleteDir();
        }
    }

    @Test
    @DisplayName("并发重复：8 线程同时提交相同序号，恰好一个成功，成交量只计一次")
    void concurrentDuplicateSingleWinner() throws Exception {
        String symbol = "CONC-DUP";
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Map<String, Integer> reasonCount = new ConcurrentHashMap<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    IngestResult r = support.service.submit(
                            support.tickJson(symbol, 1, 50, 5, 1_000));
                    reasonCount.merge(r.accepted() ? "ACCEPTED" : r.reason(), 1, Integer::sum);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, reasonCount.getOrDefault("ACCEPTED", 0), "只能有一个线程生效");
        assertEquals(threads - 1, reasonCount.getOrDefault("DUPLICATE_SEQ", 0),
                "其余线程应全部得到 DUPLICATE_SEQ");
        WindowAggregate w = support.service.queryWindowForTs(symbol, 1_000);
        assertEquals(5.0, w.volume(), 1e-9, "成交量只计一次");
        assertEquals(1, w.tickCount());
        LOG.info("  -> {} 线程抢同序号：1 个 ACCEPTED，{} 个 DUPLICATE_SEQ，volume={} ✓",
                threads, threads - 1, w.volume());
    }
}
