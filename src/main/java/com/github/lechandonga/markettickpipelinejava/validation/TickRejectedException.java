package com.github.lechandonga.markettickpipelinejava.validation;

/**
 * 行情报文被明确拒绝时抛出。拒绝原因必须可区分，不能静默丢弃。
 */
public class TickRejectedException extends RuntimeException {

    private final transient RejectReason reason;

    public TickRejectedException(RejectReason reason) {
        super(reason.message());
        this.reason = reason;
    }

    public TickRejectedException(RejectReason reason, String detail) {
        super(reason.message() + ": " + detail);
        this.reason = reason;
    }

    public RejectReason reason() {
        return reason;
    }
}
