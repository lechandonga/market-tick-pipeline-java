package com.github.lechandonga.markettickpipelinejava.model;

/**
 * 提交行情的处理结果。
 *
 * @param accepted    是否生效
 * @param duplicate   是否为重复序号（accepted=false 时可能为 true）
 * @param late        是否因窗口关闭被丢弃
 * @param reason      拒绝原因 code（被拒绝时）
 * @param message     人类可读说明
 * @param symbol      品种
 * @param seq         序号
 * @param windowStart 生效报文所属窗口起始（生效时返回）
 */
public record IngestResult(
        boolean accepted,
        boolean duplicate,
        boolean late,
        String reason,
        String message,
        String symbol,
        Long seq,
        Long windowStart
) {

    public static IngestResult accepted(Tick tick, long windowStart) {
        return new IngestResult(true, false, false, null, "accepted",
                tick.symbol(), tick.seq(), windowStart);
    }

    public static IngestResult rejected(RejectReason reason, String message, String symbol, Long seq) {
        return new IngestResult(false,
                reason == RejectReason.DUPLICATE_SEQ,
                reason == RejectReason.LATE_CLOSED_WINDOW,
                reason.code(), message, symbol, seq, null);
    }
}
