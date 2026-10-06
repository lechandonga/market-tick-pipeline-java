package com.github.lechandonga.markettickpipelinejava.model;

/**
 * 行情报文（单条成交/报价）。
 *
 * @param symbol    品种代码
 * @param seq       品种内单调递增的序号，用于去重与同时间戳的确定性排序
 * @param price     价格
 * @param volume    成交量
 * @param timestamp 行情发生时间（epoch millis）
 */
public record Tick(String symbol, long seq, double price, double volume, long timestamp) {
}
