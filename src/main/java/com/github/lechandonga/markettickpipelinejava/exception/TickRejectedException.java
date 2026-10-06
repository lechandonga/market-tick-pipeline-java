package com.github.lechandonga.markettickpipelinejava.exception;

import com.github.lechandonga.markettickpipelinejava.model.RejectReason;

/**
 * 行情报文被拒绝的业务异常，携带可区分的拒绝原因与定位字段。
 */
public class TickRejectedException extends RuntimeException {

    private final RejectReason reason;
    private final String field;
    private final String raw;
    private final String symbol;
    private final Long seq;

    public TickRejectedException(RejectReason reason, String field, String raw, String symbol, Long seq) {
        super(reason.defaultMessage());
        this.reason = reason;
        this.field = field;
        this.raw = raw;
        this.symbol = symbol;
        this.seq = seq;
    }

    public RejectReason reason() {
        return reason;
    }

    public String field() {
        return field;
    }

    public String raw() {
        return raw;
    }

    public String symbol() {
        return symbol;
    }

    public Long seq() {
        return seq;
    }
}
