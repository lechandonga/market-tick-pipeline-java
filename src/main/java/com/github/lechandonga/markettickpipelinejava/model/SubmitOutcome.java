package com.github.lechandonga.markettickpipelinejava.model;

/**
 * 提交一条行情后的处理结果。
 *
 * @param status        ACCEPTED（计入窗口） / DUPLICATE（重复报文） / LATE_DROPPED（迟到丢弃）
 * @param reason        机器可读的原因码
 * @param detail        人类可读的说明
 * @param windowStart   命中/关联窗口的起始时间（epoch millis，可能为 null）
 * @param windowEnd     命中/关联窗口的结束时间（epoch millis，可能为 null）
 */
public record SubmitOutcome(String status, String reason, String detail,
                            Long windowStart, Long windowEnd) {

    public static SubmitOutcome accepted(long windowStart, long windowEnd) {
        return new SubmitOutcome("ACCEPTED", "ACCEPTED",
                "报文已计入对应窗口", windowStart, windowEnd);
    }

    public static SubmitOutcome duplicate(long seq) {
        return new SubmitOutcome("DUPLICATE", "DUPLICATE_SEQ",
                "序号 " + seq + " 的报文已存在，重复到达不重复计价、不重复累加成交量",
                null, null);
    }

    public static SubmitOutcome lateDropped(long windowStart, long windowEnd) {
        return new SubmitOutcome("LATE_DROPPED", "WINDOW_CLOSED_LATE",
                "目标窗口已关闭，迟到报文被明确丢弃，不允许改写已关闭窗口",
                windowStart, windowEnd);
    }
}
