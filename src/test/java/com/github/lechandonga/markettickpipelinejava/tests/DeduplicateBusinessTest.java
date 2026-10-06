package com.github.lechandonga.markettickpipelinejava.tests;

import com.github.lechandonga.markettickpipelinejava.model.IngestResult;
import com.github.lechandonga.markettickpipelinejava.model.RejectReason;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务：按品种 + 序号去重。
 * 重复报文（即使内容不同、即使多次到达）只能生效一次，
 * 成交量不得重复累加，并返回可区分的 DUPLICATE_SEQ。
 */
@ExtendWith(BusinessLogExtension.class)
class DeduplicateBusinessTest {

    private static final Logger LOG = LoggerFactory.getLogger(DeduplicateBusinessTest.class);
    private static PipelineTestSupport support;

    @BeforeAll
    static void setUp() throws Exception {
        support = PipelineTestSupport.create(60_000, 60_000);
    }

    @AfterAll
    static void tearDown() throws Exception {
        support.deleteDir();
    }

    @Test
    @DisplayName("同品种同序号重复到达：只第一次生效，后续 DUPLICATE_SEQ")
    void duplicateSeqOnlyAppliesOnce() {
        String symbol = "DUP1";
        assertTrue(support.service.submit(
                support.tickJson(symbol, 1, 100, 10, 1_000)).accepted());

        // 完全相同的报文重复
        IngestResult dup1 = support.service.submit(
                support.tickJson(symbol, 1, 100, 10, 1_000));
        assertFalse(dup1.accepted());
        assertEquals(RejectReason.DUPLICATE_SEQ.code(), dup1.reason());
        assertTrue(dup1.duplicate());

        // 伪造的"重放"报文：序号相同但价格/数量不同，也绝不能二次计价
        IngestResult dup2 = support.service.submit(
                support.tickJson(symbol, 1, 999, 999, 2_000));
        assertEquals(RejectReason.DUPLICATE_SEQ.code(), dup2.reason());

        WindowAggregate w = support.service.queryWindowForTs(symbol, 1_000);
        assertEquals(1, w.tickCount(), "笔数只能是 1");
        assertEquals(10.0, w.volume(), 1e-9, "成交量不能重复累加");
        assertEquals(100.0, w.open(), 1e-9);
        assertEquals(100.0, w.close(), 1e-9);
        LOG.info("  -> 重复 2 次后 tickCount={} volume={}（未重复累加）✓", w.tickCount(), w.volume());
    }

    @Test
    @DisplayName("不同品种允许使用相同序号，互不影响")
    void seqScopedBySymbol() {
        assertTrue(support.service.submit(
                support.tickJson("AAA", 7, 10, 1, 1_000)).accepted());
        assertTrue(support.service.submit(
                support.tickJson("BBB", 7, 20, 2, 1_000)).accepted());
        assertEquals(1.0, support.service.queryWindowForTs("AAA", 1_000).volume(), 1e-9);
        assertEquals(2.0, support.service.queryWindowForTs("BBB", 1_000).volume(), 1e-9);
        LOG.info("  -> 同序号跨品种分别生效，volume AAA=1 BBB=2 ✓");
    }

    @Test
    @DisplayName("原始日志中重复报文只保留一份（重启后也不会重复累加）")
    void duplicateNotPersisted() throws Exception {
        long count = support.logStore.recoverAll().stream()
                .filter(r -> r.symbol().equals("DUP1"))
                .mapToLong(r -> r.ticks().size())
                .sum();
        assertEquals(1, count, "DUP1 的原始日志只能有 seq=1 一条");
        LOG.info("  -> DUP1 原始日志条数={}（重复报文未落盘）✓", count);
    }
}
