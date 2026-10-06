package com.github.lechandonga.markettickpipelinejava.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 被拒绝的报文记录：原始报文、拒绝原因、可定位字段与发生时间。
 * 用于持久化到 rejected.jsonl 审计日志，保证异常报文不被静默丢弃。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RejectedTick(
        String reason,
        String message,
        String field,
        String raw,
        String symbol,
        Long seq,
        long rejectedAt
) {
    public static RejectedTick of(RejectReason reason, String field, String raw, String symbol, Long seq) {
        return new RejectedTick(
                reason.code(),
                reason.defaultMessage(),
                field,
                raw,
                symbol,
                seq,
                System.currentTimeMillis());
    }
}
