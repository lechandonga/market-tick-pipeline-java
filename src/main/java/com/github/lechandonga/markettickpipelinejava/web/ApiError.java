package com.github.lechandonga.markettickpipelinejava.web;

/**
 * 统一错误响应体：可区分的拒绝原因 + 可定位字段 + 说明。
 */
public record ApiError(String reason, String message, String field, String symbol, Long seq) {
}
