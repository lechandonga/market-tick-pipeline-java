package com.github.lechandonga.markettickpipelinejava.tests;

import com.github.lechandonga.markettickpipelinejava.model.IngestResult;
import com.github.lechandonga.markettickpipelinejava.model.RejectReason;
import com.github.lechandonga.markettickpipelinejava.model.WindowAggregate;
import com.github.lechandonga.markettickpipelinejava.tests.support.BusinessLogExtension;
import com.github.lechandonga.markettickpipelinejava.tests.support.PipelineTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务：固定时间窗口 OHLC + 成交量聚合，乱序/迟到语义。
 *
 * <p>测试参数：窗口大小 10s，允许迟到 2s。
 * 水位线 watermark = 最大已见时间戳 - 2000；
 * 窗口 [w, w+10s) 在 watermark >= w+10s 后永久关闭。
 */
@ExtendWith(BusinessLogExtension.class)
class WindowAggregationBusinessTest {

    private static final Logger LOG = LoggerFactory.getLogger(WindowAggregationBusinessTest.class);
    private static final long W = 10_000;
    private static final long L = 2_000;
    private PipelineTestSupport support;

    @BeforeEach
    void setUp() throws Exception {
        support = PipelineTestSupport.create(W, L);
    }

    @AfterEach
    void tearDown() throws Exception {
        support.deleteDir();
    }

    @Test
    @DisplayName("窗口对齐：落在同一 10s 窗口的 tick 聚合到一起，窗口边界不含右端")
    void windowAlignment() {
        String s = "ALIGN";
        // 0ms 和 9999ms 同属窗口 [0,10000)；10000ms 属下一窗口
        submit(s, 1, 10, 1, 0);
        submit(s, 2, 12, 2, 9_999);
        submit(s, 3, 20, 5, 10_000);

        WindowAggregate w0 = support.service.queryWindow(s, 0);
        assertEquals(2, w0.tickCount());
        assertEquals(3.0, w0.volume(), 1e-9);
        assertEquals(0, w0.windowStart());
        assertEquals(10_000, w0.windowEnd());

        WindowAggregate w1 = support.service.queryWindow(s, 10_000);
        assertEquals(1, w1.tickCount());
        assertEquals(5.0, w1.volume(), 1e-9);
        LOG.info("  -> [0,10s): count={} volume={}；[10s,20s): count={} volume={} ✓",
                w0.tickCount(), w0.volume(), w1.tickCount(), w1.volume());
    }

    @Test
    @DisplayName("乱序到达：按业务时间戳而非到达顺序计算 open/close，high/low/volume 正确")
    void outOfOrderOHLC() {
        String s = "OOO";
        // 故意打乱到达顺序
        submit(s, 3, 30, 3, 5_000);
        submit(s, 1, 10, 1, 1_000);
        submit(s, 4, 40, 4, 7_000);
        submit(s, 2, 5, 2, 3_000);

        WindowAggregate w = support.service.queryWindow(s, 0);
        assertEquals(10.0, w.open(), 1e-9, "open 应为最早时间戳价格 10");
        assertEquals(40.0, w.close(), 1e-9, "close 应为最晚时间戳价格 40");
        assertEquals(40.0, w.high(), 1e-9);
        assertEquals(5.0, w.low(), 1e-9);
        assertEquals(10.0, w.volume(), 1e-9, "1+2+3+4");
        assertEquals(4, w.tickCount());
        LOG.info("  -> 乱序后 open={} high={} low={} close={} volume={} ✓",
                w.open(), w.high(), w.low(), w.close(), w.volume());
    }

    @Test
    @DisplayName("同一时间戳多笔：open 取 seq 小者、close 取 seq 大者，结果与顺序无关")
    void sameTimestampTieBreak() {
        String a = "TIE-A";
        String b = "TIE-B";
        // A 顺序 1 -> 2，B 顺序 2 -> 1，聚合必须一致
        submit(a, 1, 11, 1, 2_000);
        submit(a, 2, 22, 1, 2_000);
        submit(b, 2, 22, 1, 2_000);
        submit(b, 1, 11, 1, 2_000);

        WindowAggregate wa = support.service.queryWindow(a, 0);
        WindowAggregate wb = support.service.queryWindow(b, 0);
        assertEquals(wa.open(), wb.open(), 1e-9);
        assertEquals(wa.high(), wb.high(), 1e-9);
        assertEquals(wa.low(), wb.low(), 1e-9);
        assertEquals(wa.close(), wb.close(), 1e-9);
        assertEquals(wa.volume(), wb.volume(), 1e-9);
        assertEquals(wa.tickCount(), wb.tickCount());
        assertEquals(wa.closed(), wb.closed());
        assertEquals(11.0, wa.open(), 1e-9);
        assertEquals(22.0, wa.close(), 1e-9);
        LOG.info("  -> 同时间戳 tie-break open={} close={}，两种到达顺序结果相等 ✓",
                wa.open(), wa.close());
    }

    @Test
    @DisplayName("迟到但窗口未关：在允许迟到范围内并入正确窗口，不丢量")
    void lateButWithinAllowedLateness() {
        String s = "LATE-OK";
        submit(s, 1, 10, 1, 1_000);
        // 先到窗口1 的 tick（ts=10500），watermark=8500，窗口0 仍开
        submit(s, 2, 20, 2, 10_500);
        // 迟到 tick：ts=9000 属窗口0，此时 watermark=8500 < 10000，窗口0 尚未关闭
        IngestResult late = support.service.submit(
                support.tickJson(s, 3, 99, 7, 9_000));
        assertTrue(late.accepted(), "允许迟到范围内应进入正确窗口");
        WindowAggregate w0 = support.service.queryWindow(s, 0);
        // 窗口0 = seq1(ts=1000, vol1) + 迟到 seq3(ts=9000, vol7)，seq2 在窗口1
        assertEquals(2, w0.tickCount());
        assertEquals(8.0, w0.volume(), 1e-9, "迟到量不能丢：1+7");
        assertEquals(99.0, w0.close(), 1e-9, "9000ms 是窗口内最晚 tick");
        LOG.info("  -> 窗口未关，迟到 tick 并入窗口0：count={} volume={} close={} ✓",
                w0.tickCount(), w0.volume(), w0.close());
    }

    @Test
    @DisplayName("窗口关闭后迟到：明确 LATE_CLOSED_WINDOW 丢弃，已关闭窗口不被改写")
    void lateAfterWindowClosed() {
        String s = "LATE-GONE";
        submit(s, 1, 10, 1, 0);
        submit(s, 2, 11, 2, 5_000);
        // 推进时间：ts=20000 -> watermark=18000 >= 10000，窗口0 永久关闭
        submit(s, 3, 30, 3, 20_000);

        WindowAggregate before = support.service.queryWindow(s, 0);
        assertTrue(before.closed(), "窗口0 应已标记关闭");

        IngestResult rejected = support.service.submit(
                support.tickJson(s, 4, 999, 999, 1_000));
        assertFalse(rejected.accepted());
        assertEquals(RejectReason.LATE_CLOSED_WINDOW.code(), rejected.reason());
        assertTrue(rejected.late());

        WindowAggregate after = support.service.queryWindow(s, 0);
        assertEquals(before, after, "已关闭窗口不能被迟到数据改写");
        assertEquals(3.0, after.volume(), 1e-9, "成交量不增加");
        assertEquals(2, after.tickCount());
        assertEquals(11.0, after.close(), 1e-9, "close 不被改写");
        LOG.info("  -> 窗口关闭后迟到 tick 被明确丢弃（LATE_CLOSED_WINDOW），窗口结果不变 ✓");
    }

    @Test
    @DisplayName("综合：多窗口乱序 + 关闭边界，任意顺序下既不重复计价也不丢量")
    void mixedScenarioNoDoubleCountNoLoss() {
        String s = "MIX";
        submit(s, 10, 100, 10, 1_000);
        submit(s, 12, 120, 12, 11_000);
        // 乱序：窗口1 的 ts=15000 先到，再回补同窗口的 ts=12000（窗口1 仍开放）
        submit(s, 11, 110, 11, 15_000);
        submit(s, 9, 90, 9, 12_000);

        List<WindowAggregate> windows = support.service.query(s);
        // seq10 在窗口0；seq11(15s)/seq12(11s)/seq9(12s) 都在窗口10s
        assertEquals(2, windows.size());
        double totalVolume = windows.stream().mapToDouble(WindowAggregate::volume).sum();
        assertEquals(42.0, totalVolume, 1e-9, "总量 = 9+10+11+12=42，乱序不丢量");
        long totalCount = windows.stream().mapToLong(WindowAggregate::tickCount).sum();
        assertEquals(4, totalCount);
        WindowAggregate w1 = support.service.queryWindow(s, 10_000);
        assertEquals(120.0, w1.open(), 1e-9, "窗口1 open 取最早 ts=11s 价格 120");
        assertEquals(110.0, w1.close(), 1e-9, "窗口1 close 取最晚 ts=15s 价格 110");
        assertEquals(120.0, w1.high(), 1e-9);
        assertEquals(90.0, w1.low(), 1e-9);

        // 重复 + 迟到都不能改变总量
        support.service.submit(support.tickJson(s, 11, 110, 11, 15_000)); // 重复
        submit(s, 13, 130, 13, 20_000);  // 推进时间关闭窗口0
        support.service.submit(support.tickJson(s, 99, 1, 1, 1_000));     // 窗口0 已关
        List<WindowAggregate> after = support.service.query(s);
        double volumeAfter = after.stream().mapToDouble(WindowAggregate::volume).sum();
        assertEquals(55.0, volumeAfter, 1e-9, "重复/迟到后总量为 42+13=55");
        LOG.info("  -> 乱序总量={}，注入重复与关闭后迟到后总量={} ✓", totalVolume, volumeAfter);
    }

    private void submit(String symbol, long seq, double price, double qty, long ts) {
        IngestResult r = support.service.submit(support.tickJson(symbol, seq, price, qty, ts));
        assertTrue(r.accepted(), "测试数据应被接受: " + symbol + " seq=" + seq + " 实际原因: " + r.reason());
    }
}
