package com.github.lechandonga.markettickpipelinejava.tests;

import com.github.lechandonga.markettickpipelinejava.model.IngestResult;
import com.github.lechandonga.markettickpipelinejava.model.RejectReason;
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
 * 业务：行情报文接入与校验。
 * 覆盖：合法报文、字段缺失、类型错误、取值异常（价格/数量/时间戳/序号/品种）、
 * 非法 JSON，以及拒绝必须落审计日志（不静默丢弃）。
 */
@ExtendWith(BusinessLogExtension.class)
class ValidationBusinessTest {

    private static final Logger LOG = LoggerFactory.getLogger(ValidationBusinessTest.class);
    private static PipelineTestSupport support;

    @BeforeAll
    static void setUp() throws Exception {
        support = PipelineTestSupport.create(60_000, 5_000);
    }

    @AfterAll
    static void tearDown() throws Exception {
        support.deleteDir();
    }

    private void assertRejected(String json, RejectReason expected) {
        IngestResult result = support.service.submit(json);
        assertFalse(result.accepted(), "应被拒绝: " + expected);
        assertEquals(expected.code(), result.reason(),
                "拒绝原因应可区分，期望 " + expected + " 实际 " + result.reason());
        LOG.info("  -> 拒绝原因={} 可区分 ✓ 报文={}", result.reason(), json);
    }

    @Test
    @DisplayName("合法报文被接受")
    void validTickAccepted() {
        IngestResult result = support.service.submit(
                support.tickJson("IF2401", 1, 100.5, 3, 1_000));
        assertTrue(result.accepted());
        assertEquals(0L, result.windowStart());
        LOG.info("  -> accepted symbol={} windowStart={}", result.symbol(), result.windowStart());
    }

    @Test
    @DisplayName("缺少字段：缺 price / quantity / timestamp 分别报 MISSING_FIELD")
    void missingFieldsRejected() {
        assertRejected("{\"symbol\":\"A\",\"seq\":1,\"quantity\":1,\"timestamp\":1}",
                RejectReason.MISSING_FIELD);
        assertRejected("{\"symbol\":\"A\",\"seq\":1,\"price\":1,\"timestamp\":1}",
                RejectReason.MISSING_FIELD);
        assertRejected("{\"symbol\":\"A\",\"seq\":1,\"price\":1,\"quantity\":1}",
                RejectReason.MISSING_FIELD);
        assertRejected("{\"symbol\":\"A\",\"price\":1,\"quantity\":1,\"timestamp\":1}",
                RejectReason.MISSING_FIELD);
        assertRejected("{\"seq\":1,\"price\":1,\"quantity\":1,\"timestamp\":1}",
                RejectReason.MISSING_FIELD);
    }

    @Test
    @DisplayName("字段类型错误：price 传字符串、seq 传小数、timestamp 传布尔 → INVALID_TYPE")
    void invalidTypeRejected() {
        assertRejected("{\"symbol\":\"A\",\"seq\":1,\"price\":\"10\",\"quantity\":1,\"timestamp\":1}",
                RejectReason.INVALID_TYPE);
        assertRejected("{\"symbol\":\"A\",\"seq\":1.5,\"price\":10,\"quantity\":1,\"timestamp\":1}",
                RejectReason.INVALID_TYPE);
        assertRejected("{\"symbol\":\"A\",\"seq\":1,\"price\":10,\"quantity\":1,\"timestamp\":true}",
                RejectReason.INVALID_TYPE);
    }

    @Test
    @DisplayName("取值异常：价格<=0/NaN/无穷、数量<=0、时间戳<0、序号<1、空白品种")
    void invalidValueRejected() {
        assertRejected(support.tickJson("A", 2, 0, 1, 1), RejectReason.INVALID_PRICE);
        assertRejected(support.tickJson("A", 3, -5, 1, 1), RejectReason.INVALID_PRICE);
        assertRejected("{\"symbol\":\"A\",\"seq\":4,\"price\":NaN,\"quantity\":1,\"timestamp\":1}",
                RejectReason.INVALID_PRICE);
        assertRejected("{\"symbol\":\"A\",\"seq\":5,\"price\":Infinity,\"quantity\":1,\"timestamp\":1}",
                RejectReason.INVALID_PRICE);
        assertRejected(support.tickJson("A", 6, 10, 0, 1), RejectReason.INVALID_QUANTITY);
        assertRejected(support.tickJson("A", 7, 10, -2, 1), RejectReason.INVALID_QUANTITY);
        assertRejected(support.tickJson("A", 8, 10, 1, -1), RejectReason.INVALID_TIMESTAMP);
        assertRejected(support.tickJson("A", 0, 10, 1, 1), RejectReason.INVALID_SEQ);
        assertRejected(support.tickJson("   ", 9, 10, 1, 1), RejectReason.BLANK_SYMBOL);
    }

    @Test
    @DisplayName("报文语法/结构错误：MALFORMED_JSON 与 NOT_AN_OBJECT")
    void malformedRejected() {
        assertRejected("{not-json", RejectReason.MALFORMED_JSON);
        assertRejected("", RejectReason.MALFORMED_JSON);
        assertRejected("[1,2,3]", RejectReason.NOT_AN_OBJECT);
        assertRejected("\"a string\"", RejectReason.NOT_AN_OBJECT);
    }

    @Test
    @DisplayName("所有拒绝都写入 rejected.jsonl 审计日志，绝不静默丢弃")
    void rejectsAreAudited() throws Exception {
        PipelineTestSupport local = PipelineTestSupport.create(60_000, 5_000);
        try {
            int rejects = 0;
            local.service.submit("{bad"); rejects++;
            local.service.submit("[1]"); rejects++;
            local.service.submit(support.tickJson("A", 1, 0, 1, 1)); rejects++;
            local.service.submit(support.tickJson("A", 2, 10, -1, 1)); rejects++;
            assertEquals(rejects, local.rejectedAuditLines(),
                    "每条拒绝报文都应在 rejected.jsonl 中留下带原因的记录");
            LOG.info("  -> {} 条拒绝全部落入 rejected.jsonl ✓", rejects);
        } finally {
            local.deleteDir();
        }
    }
}
