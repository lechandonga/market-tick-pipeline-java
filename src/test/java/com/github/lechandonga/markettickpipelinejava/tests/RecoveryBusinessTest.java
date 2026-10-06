package com.github.lechandonga.markettickpipelinejava.tests;

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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务：重启恢复。
 * 进程"重启"（丢弃全部内存状态，只保留本地 data 目录）后，从 append-only
 * 原始报文日志重放，恢复出的窗口聚合必须与重启前逐窗口一致；
 * 重启后继续提交，窗口关闭与聚合仍然正确。
 */
@ExtendWith(BusinessLogExtension.class)
class RecoveryBusinessTest {

    private static final Logger LOG = LoggerFactory.getLogger(RecoveryBusinessTest.class);
    private static PipelineTestSupport before;

    @BeforeAll
    static void setUp() throws Exception {
        before = PipelineTestSupport.create(10_000, 2_000);
        // 品种 R1：
        // 窗口 [0,10s) 内乱序三笔（先到 ts=3000，再到 ts=1000，保证乱序但窗口未关）
        before.service.submit(before.tickJson("R1", 3, 30, 3, 3_000));
        before.service.submit(before.tickJson("R1", 1, 10, 1, 1_000));
        // 重复，不落盘
        before.service.submit(before.tickJson("R1", 1, 999, 999, 1_000));
        before.service.submit(before.tickJson("R1", 2, 20, 2, 8_000));
        // 下一窗口一笔
        before.service.submit(before.tickJson("R1", 4, 40, 4, 12_000));
        // 推进水位线（maxTs=20000 -> watermark=18000），窗口0 关闭
        before.service.submit(before.tickJson("R1", 5, 50, 5, 20_000));
        // 迟到进已关闭的窗口0：被丢弃、不落盘
        before.service.submit(before.tickJson("R1", 6, 1, 1, 1_000));

        before.service.submit(before.tickJson("R2", 1, 7, 6, 500));
        before.service.submit(before.tickJson("R2", 2, 8, 8, 550));
    }

    @AfterAll
    static void tearDown() throws Exception {
        before.deleteDir();
    }

    @Test
    @DisplayName("重启前基线：多品种、乱序、含重复与迟到拒绝")
    void seedBeforeRestart() {
        List<WindowAggregate> baseR1 = before.service.query("R1");
        double totalR1 = baseR1.stream().mapToDouble(WindowAggregate::volume).sum();
        assertEquals(15.0, totalR1, 1e-9, "重启前 R1 总量 1+2+3+4+5=15（迟到 seq6 丢弃）");
        LOG.info("  -> 重启前基线：R1 窗口数={} 总量={}，R2 总量={} ✓",
                baseR1.size(), totalR1,
                before.service.query("R2").stream().mapToDouble(WindowAggregate::volume).sum());
    }

    @Test
    @DisplayName("重启后：仅靠本地原始日志恢复，窗口结果与重启前逐窗口完全一致")
    void recoverFromLocalLogs() throws Exception {
        PipelineTestSupport after = before.restart();
        after.replayAll();

        for (String symbol : List.of("R1", "R2")) {
            List<WindowAggregate> beforeWindows = before.service.query(symbol);
            List<WindowAggregate> afterWindows = after.service.query(symbol);
            assertEquals(beforeWindows, afterWindows,
                    symbol + " 恢复后的窗口聚合必须与重启前一致");
        }

        List<WindowAggregate> r1 = after.service.query("R1");
        WindowAggregate w0 = r1.stream().filter(w -> w.windowStart() == 0).findFirst().orElseThrow();
        assertEquals(10.0, w0.open(), 1e-9);
        assertEquals(20.0, w0.close(), 1e-9, "窗口内最晚 ts=8000 价格 20");
        assertEquals(30.0, w0.high(), 1e-9);
        assertEquals(10.0, w0.low(), 1e-9);
        assertEquals(6.0, w0.volume(), 1e-9, "1+2+3");
        assertEquals(3, w0.tickCount());
        assertTrue(w0.closed(), "水位线随重放重建，窗口0 应为关闭状态");
        LOG.info("  -> 恢复后 R1/R2 全部窗口与重启前一致；窗口0 closed={} ✓", w0.closed());
    }

    @Test
    @DisplayName("重启后迟到旧窗口仍被拒绝，新数据正常聚合，结果可继续复现")
    void continueAfterRecovery() throws Exception {
        PipelineTestSupport after = before.restart();
        after.replayAll();

        // 水位线已恢复（maxTs=20000，wm=18000），再发窗口0 的 tick 仍必须丢弃
        var late = after.service.submit(after.tickJson("R1", 100, 1, 1, 500));
        assertEquals("LATE_CLOSED_WINDOW", late.reason());

        // 向已存在的开放窗口20000 追加一笔
        var ok = after.service.submit(after.tickJson("R1", 7, 55, 5, 25_000));
        assertTrue(ok.accepted());
        WindowAggregate w2 = after.service.queryWindow("R1", 20_000);
        assertEquals(10.0, w2.volume(), 1e-9, "重启前 seq5=5 + 重启后 seq7=5");

        // 再次"重启"，追加的一笔也必须恢复出来
        PipelineTestSupport after2 = after.restart();
        after2.replayAll();
        WindowAggregate w2Again = after2.service.queryWindow("R1", 20_000);
        assertEquals(w2, w2Again, "第二次重启后结果仍一致");
        LOG.info("  -> 重启后拒绝旧迟到 ✓ 新 tick volume={}，二次重启仍一致 ✓", w2.volume());
    }
}
