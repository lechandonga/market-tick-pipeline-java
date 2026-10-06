package com.github.lechandonga.markettickpipelinejava.tests;

import com.github.lechandonga.markettickpipelinejava.model.SubmitOutcome;
import com.github.lechandonga.markettickpipelinejava.model.Tick;
import com.github.lechandonga.markettickpipelinejava.model.WindowAggregate;
import com.github.lechandonga.markettickpipelinejava.service.TickPipelineService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.github.lechandonga.markettickpipelinejava.tests.TestSupport.WINDOW;
import static com.github.lechandonga.markettickpipelinejava.tests.TestSupport.baseWindowStart;
import static com.github.lechandonga.markettickpipelinejava.tests.TestSupport.newDataDir;
import static com.github.lechandonga.markettickpipelinejava.tests.TestSupport.newService;
import static com.github.lechandonga.markettickpipelinejava.tests.TestSupport.tick;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 业务四：重启恢复。仅依赖本地持久化的原始报文日志，重建出与重启前一致的窗口结果。
 */
@DisplayName("业务四：重启恢复——本地日志重放结果与重启前一致")
class RecoveryTest {

    private static final Logger log = LoggerFactory.getLogger(RecoveryTest.class);

    @Test
    @DisplayName("4.1 重启后从本地日志恢复：正常窗口、重复序号、迟到丢弃决策全部复现")
    void recoverAfterRestart() throws Exception {
        Path dir = newDataDir();
        long t0 = baseWindowStart(WINDOW);

        TickPipelineService before = newService(dir);
        // 窗口1：3 笔正常 + 1 笔重复
        before.submit(tick("REC", 1L, 100.0, 1.0, t0 + 1_000));
        before.submit(tick("REC", 3L, 105.0, 3.0, t0 + 30_000));
        before.submit(tick("REC", 2L, 98.0, 2.0, t0 + 10_000));
        SubmitOutcome dup = before.submit(tick("REC", 1L, 1.0, 1.0, t0 + 1_000));
        // 推进 watermark 关闭窗口1
        before.submit(tick("REC", 4L, 120.0, 4.0, t0 + WINDOW + 10_000));
        // 窗口1 的迟到报文：重启前被丢弃
        SubmitOutcome late = before.submit(tick("REC", 5L, 1.0, 999.0, t0 + 5_000));

        assertEquals("DUPLICATE", dup.status());
        assertEquals("LATE_DROPPED", late.status());

        List<WindowAggregate> beforeWindows = before.query("REC");
        WindowAggregate beforeW0 = before.queryWindow("REC", t0);
        WindowAggregate beforeW1 = before.queryWindow("REC", t0 + WINDOW);
        log.info("[用例] 重启前：窗口1 open={} high={} low={} close={} volume={} closed={}；窗口2 volume={}；迟到 seq=5 已丢弃",
                beforeW0.open(), beforeW0.high(), beforeW0.low(), beforeW0.close(),
                beforeW0.volume(), beforeW0.closed(), beforeW1.volume());

        long journalLines = Files.readAllLines(dir.resolve("tick-journal.log")).size();
        assertEquals(5, journalLines, "3 正常 + 1 关闭窗口报文 + 1 迟到丢弃记录");

        // ===== 模拟进程重启：丢弃全部内存状态，只保留数据目录 =====
        TickPipelineService after = newService(dir);
        log.info("[用例] 模拟重启：重新从 {} 读取本地日志并重放", dir);

        List<WindowAggregate> afterWindows = after.query("REC");
        WindowAggregate afterW0 = after.queryWindow("REC", t0);
        WindowAggregate afterW1 = after.queryWindow("REC", t0 + WINDOW);

        assertNotNull(afterW0);
        assertEquals(beforeWindows.size(), afterWindows.size());
        assertEquals(beforeW0.open(), afterW0.open());
        assertEquals(beforeW0.high(), afterW0.high());
        assertEquals(beforeW0.low(), afterW0.low());
        assertEquals(beforeW0.close(), afterW0.close());
        assertEquals(beforeW0.volume(), afterW0.volume(), 0.0001);
        assertEquals(beforeW0.tickCount(), afterW0.tickCount());
        assertEquals(beforeW0.closed(), afterW0.closed());
        assertEquals(beforeW1.volume(), afterW1.volume(), 0.0001);
        log.info("[用例] 重启后：窗口1 open={} high={} low={} close={} volume={} closed={}；窗口2 volume={} —— 与重启前完全一致",
                afterW0.open(), afterW0.high(), afterW0.low(), afterW0.close(),
                afterW0.volume(), afterW0.closed(), afterW1.volume());

        // 迟到报文 seq=5 恢复后仍为“已处理”：再次提交同序号仍然识别为重复，
        // 不会因为重启把 999 量重新累加。
        SubmitOutcome replayLate = after.submit(tick("REC", 5L, 1.0, 999.0, t0 + 5_000));
        assertEquals("DUPLICATE", replayLate.status());
        double totalVolume = afterW0.volume() + afterW1.volume();
        assertEquals(10.0, totalVolume, 0.0001, "重启后迟到报文仍不产生任何成交量");
        log.info("[用例] 重启后重提 seq=5 判定为 {}，两窗口总量仍为 {}（1+2+3+4，迟到的 999 从未计入）",
                replayLate.status(), totalVolume);
    }
}
