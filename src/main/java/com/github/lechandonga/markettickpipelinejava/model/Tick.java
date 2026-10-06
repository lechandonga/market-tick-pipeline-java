package com.github.lechandonga.markettickpipelinejava.model;

/**
 * 一条经过校验、被接受的行情报文。
 *
 * @param symbol   品种代码
 * @param seq      品种内单调递增的报文序号
 * @param price    成交价（必须为有限正数）
 * @param quantity 成交数量（必须为有限正数）
 * @param ts       交易所时间戳，epoch 毫秒（必须为正数）
 */
public record Tick(String symbol, long seq, double price, double quantity, long ts) {
}
