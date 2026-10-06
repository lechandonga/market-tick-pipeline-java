package com.github.lechandonga.markettickpipelinejava.tests;

import com.github.lechandonga.markettickpipelinejava.model.SubmitOutcome;
import com.github.lechandonga.markettickpipelinejava.model.Tick;
import com.github.lechandonga.markettickpipelinejava.model.WindowAggregate;
import com.github.lechandonga.markettickpipelinejava.service.TickPipelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static com.github.lechandonga.markettickpipelinejava.tests.TestSupport.WINDOW;
import static com.github.lechandonga.markettickpipelinejava.tests.TestSupport.baseWindowStart;
import static com.github.lechandonga.markettickpipelinejava.tests.TestSupport.newDataDir;
import static com.github.lechandonga.markettickpipelinejava.tests.TestSupport.newService;
import static com.github.lechandonga.markettickpipelinejava.tests.TestSupport.tick;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务二：序号去重 + 固定窗口 OHLCV + 乱序/迟到语义。
 */
@DisplayName("业务二：去重、窗口聚合、乱序与迟到处理")
class DuplicateAndWindowTest {

    private static final Logger log = LoggerFactory.getLogger(DuplicateAndWindowTest.class);

    private Path dir;
    private TickPipelineService service;
    private long t0;

    @BeforeEach
    void setUp() throws Exception {
        dir = newDataDir();
        service = newService(dir);
        t0 = baseWindowStart(WINDOW);
        log.info("==== 场景初始化：窗口基准起点 t0={}, 窗口大小={}ms, 数据目录={} ====",
                t0, WINDOW, dir);
    }

    @Test
    @DisplayName("2.1 重复序号只生效一次：不重复计价、不重复累加成交量")
    void duplicateSeqAppliedOnce() {
        Tick first = tick("DUP", 1L, 100.0, 10.0, t0 + 1_000);
        Tick repeat = tick("DUP", 1L, 999.0, 999.0, t0 + 1_000);

        SubmitOutcome o1 = service.submit(first);
        SubmitOutcome o2 = service.submit(repeat);
        SubmitOutcome o3 = service.submit(tick("DUP", 1L, 100.0, 10.0, t0 + 1_000));

        assertEquals("ACCEPTED", o1.status());
        assertEquals("DUPLICATE", o2.status());
        assertEquals("DUPLICATE_SEQ", o2.reason());
        assertEquals("DUPLICATE", o3.status());
        log.info("[用例] 同序号报文提交 3 次：ACCEPTED x1, DUPLICATE x2，重复报文的异常价格/数量被忽略");

        WindowAggregate w = service.queryWindow("DUP", t0);
        assertEquals(100.0, w.open());
        assertEquals(100.0, w.high());
        assertEquals(100.0, w.low());
        assertEquals(100.0, w.close());
        assertEquals(10.0, w.volume());
        assertEquals(1, w.tickCount());
        log.info("[用例] 窗口聚合 open/high/low/close={}/{}/{}, volume={}, tickCount={}（成交量未被重复累加）",
                w.open(), w.high(), w.low(), w.close(), w.volume(), w.tickCount());
    }

    @Test
    @DisplayName("2.2 窗口内乱序到达：OHLCV 由事件时间决定，与到达顺序无关")
    void outOfOrderWithinWindow() {
        // 故意逆序提交，且最大时间戳先到
        service.submit(tick("OOO", 4L, 110.0, 4.0, t0 + 40_000));
        service.submit(tick("OOO", 2L, 104.0, 2.0, t0 + 20_000));
        service.submit(tick("OOO", 3L, 101.0, 3.0, t0 + 30_000));
        service.submit(tick("OOO", 1L, 108.0, 1.0, t0 + 10_000));

        WindowAggregate w = service.queryWindow("OOO", t0);
        assertEquals(108.0, w.open(), "open 应为事件时间最早(seq1)的价格");
        assertEquals(110.0, w.high());
        assertEquals(101.0, w.low());
        assertEquals(110.0, w.close(), "close 应为事件时间最晚(seq4)的价格");
        assertEquals(10.0, w.volume());
        assertEquals(4, w.tickCount());
        log.info("[用例] 逆序提交 4 笔：open={}(最早), high={}, low={}, close={}(最晚), volume={} 与顺序无关",
                w.open(), w.high(), w.low(), w.close(), w.volume());
    }

    @Test
    @DisplayName("2.3 已关闭窗口不被迟到数据改写：迟到报文明确标记 LATE_DROPPED")
    void lateDataAfterWindowClosedIsDropped() {
        // 先用 t0+2W 的报文把 watermark 推过第一个窗口结束时间，关闭窗口 [t0,t0+W)
        Tick closer = tick("LATE", 10L, 200.0, 5.0, t0 + 2 * WINDOW - 1);
        SubmitOutcome closeOutcome = service.submit(closer);
        assertEquals("ACCEPTED", closeOutcome.status());

        WindowAggregate before = service.queryWindow("LATE", t0 + 2 * WINDOW - WINDOW);
        log.info("[用例] watermark 推进至 {}，窗口 [t0, t0+W) 处于关闭状态", closer.timestamp());

        // 迟到报文属于已关闭窗口 [t0,t0+W)
        Tick late = tick("LATE", 11L, 50.0, 100.0, t0 + 5_000);
        SubmitOutcome outcome = service.submit(late);

        assertEquals("LATE_DROPPED", outcome.status());
        assertEquals("WINDOW_CLOSED_LATE", outcome.reason());
        assertEquals(t0, outcome.windowStart());
        assertEquals(t0 + WINDOW, outcome.windowEnd());
        log.info("[用例] 迟到报文 seq=11 命中已关闭窗口 [{},{}) -> {}（明确丢弃，不静默）",
                outcome.windowStart(), outcome.windowEnd(), outcome.status());

        // 关闭窗口不存在（从未有数据进入）且不会被迟到报文创建；成交量没有被加到别处
        WindowAggregate closedWindow = service.queryWindow("LATE", t0);
        // 再补一笔当前开启窗口的数据，确认系统正常计价，且迟到的 100 量没有串入
        service.submit(tick("LATE", 12L, 210.0, 7.0, t0 + 2 * WINDOW + 1_000));
        WindowAggregate openWindow = service.queryWindow("LATE", t0 + 2 * WINDOW);
        assertEquals(7.0, openWindow.volume(), "迟到报文的成交量绝不能串入其他窗口");
        assertEquals(200.0, closedWindow == null ? 200.0 : closedWindow.high(),
                "已关闭窗口不得被改写");
        log.info("[用例] 校验通过：已关闭窗口未被创建/改写，后续窗口 volume={} 未包含迟到量 100",
                openWindow.volume());
    }

    @Test
    @DisplayName("2.4 窗口尚未关闭时的迟到（乱序）报文进入正确窗口")
    void lateButWindowOpenIsAccepted() {
        service.submit(tick("OPEN", 1L, 100.0, 1.0, t0 + 50_000));
        // 时间更早但同一窗口仍然开启
        SubmitOutcome outcome = service.submit(tick("OPEN", 2L, 90.0, 2.0, t0 + 1_000));
        assertEquals("ACCEPTED", outcome.status());
        assertEquals(t0, outcome.windowStart());
        assertFalse(service.queryWindow("OPEN", t0).closed());

        WindowAggregate w = service.queryWindow("OPEN", t0);
        assertEquals(90.0, w.open());
        assertEquals(100.0, w.close());
        assertEquals(3.0, w.volume());
        log.info("[用例] 窗口开启时的乱序报文进入正确窗口：open={}, close={}, volume={}",
                w.open(), w.close(), w.volume());
    }

    @Test
    @DisplayName("2.5 窗口关闭标记随 watermark 正确翻转")
    void closedFlagFlips() {
        service.submit(tick("FLAG", 1L, 100.0, 1.0, t0 + 10_000));
        assertFalse(service.queryWindow("FLAG", t0).closed());
        service.submit(tick("FLAG", 2L, 101.0, 1.0, t0 + WINDOW + 1));
        WindowAggregate closed = service.queryWindow("FLAG", t0);
        assertTrue(closed.closed());
        log.info("[用例] watermark 越过窗口右端后 closed=true，且窗口数值冻结 open={} close={} volume={}",
                closed.open(), closed.close(), closed.volume());
    }
}
