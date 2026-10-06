package com.github.lechandonga.markettickpipelinejava.model;

/**
 * 固定时间窗口的 OHLCV 聚合结果。
 *
 * @param symbol      品种代码
 * @param windowStart 窗口起始时间（epoch millis，左闭）
 * @param windowEnd   窗口结束时间（epoch millis，右开）
 * @param open        开盘价
 * @param high        最高价
 * @param low         最低价
 * @param close       收盘价
 * @param volume      成交量之和
 * @param tickCount   参与聚合的报文数（去重后、且未被迟到丢弃）
 * @param closed      窗口是否已关闭（关闭后不可再被迟到数据改写）
 */
public record WindowAggregate(String symbol, long windowStart, long windowEnd,
                              double open, double high, double low, double close,
                              double volume, long tickCount, boolean closed) {
}
