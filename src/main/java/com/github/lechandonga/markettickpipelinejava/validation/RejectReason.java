package com.github.lechandonga.markettickpipelinejava.validation;

import org.springframework.http.HttpStatus;

/**
 * 报文拒绝原因码。每一种校验失败都有独立、可区分的原因，绝不静默丢弃。
 */
public enum RejectReason {

    MISSING_SYMBOL("MISSING_SYMBOL", "缺少品种代码 symbol"),
    MISSING_SEQ("MISSING_SEQ", "缺少序号 seq"),
    MISSING_PRICE("MISSING_PRICE", "缺少价格 price"),
    MISSING_VOLUME("MISSING_VOLUME", "缺少数量 volume"),
    MISSING_TIMESTAMP("MISSING_TIMESTAMP", "缺少时间戳 timestamp"),

    BLANK_SYMBOL("BLANK_SYMBOL", "品种代码 symbol 不能为空串"),
    INVALID_PRICE("INVALID_PRICE", "价格 price 必须为有限正数"),
    INVALID_VOLUME("INVALID_VOLUME", "数量 volume 必须为有限非负数"),
    INVALID_TIMESTAMP("INVALID_TIMESTAMP", "时间戳 timestamp 非法（非正数或偏离允许范围）"),
    SEQ_OUT_OF_RANGE("SEQ_OUT_OF_RANGE", "序号 seq 必须为正整数"),

    MALFORMED_JSON("MALFORMED_JSON", "请求体不是合法的行情 JSON 报文");

    private final String code;
    private final String message;

    RejectReason(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    public HttpStatus httpStatus() {
        return this == MALFORMED_JSON ? HttpStatus.BAD_REQUEST : HttpStatus.UNPROCESSABLE_ENTITY;
    }
}
