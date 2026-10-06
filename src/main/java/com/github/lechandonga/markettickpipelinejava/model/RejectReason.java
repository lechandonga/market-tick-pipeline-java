package com.github.lechandonga.markettickpipelinejava.model;

/**
 * 报文被拒绝的可区分原因。每种原因对应独立的 reasonCode，
 * 报文不会被静默丢弃：HTTP 响应返回原因，同时落入本地 rejected 审计日志。
 */
public enum RejectReason {

    /** 请求体不是合法 JSON。 */
    MALFORMED_JSON("MALFORMED_JSON", "请求体不是合法的 JSON"),

    /** JSON 根节点不是对象。 */
    NOT_AN_OBJECT("NOT_AN_OBJECT", "报文根节点必须是 JSON 对象"),

    /** 缺少 symbol / seq / price / quantity / timestamp 之一。 */
    MISSING_FIELD("MISSING_FIELD", "缺少必填字段"),

    /** 字段类型不合法，例如 price 传成字符串或布尔。 */
    INVALID_TYPE("INVALID_TYPE", "字段类型不合法"),

    /** symbol 为空白字符串。 */
    BLANK_SYMBOL("BLANK_SYMBOL", "symbol 不能为空或纯空白"),

    /** 序号不是正整数。 */
    INVALID_SEQ("INVALID_SEQ", "seq 必须是 >= 1 的整数"),

    /** 价格缺失、非有限数或 <= 0。 */
    INVALID_PRICE("INVALID_PRICE", "price 必须是有限正数"),

    /** 数量缺失、非有限数或 <= 0。 */
    INVALID_QUANTITY("INVALID_QUANTITY", "quantity 必须是有限正数"),

    /** 时间戳缺失或为负数。 */
    INVALID_TIMESTAMP("INVALID_TIMESTAMP", "timestamp 必须是 >= 0 的 epoch 毫秒"),

    /** 同一品种序号重复，只能生效一次。 */
    DUPLICATE_SEQ("DUPLICATE_SEQ", "同一品种该 seq 已存在，重复报文只生效一次"),

    /** 所属窗口已按水位线关闭，迟到数据被明确丢弃。 */
    LATE_CLOSED_WINDOW("LATE_CLOSED_WINDOW", "报文所属窗口已关闭，迟到数据被丢弃");

    private final String code;
    private final String defaultMessage;

    RejectReason(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
