package com.github.lechandonga.markettickpipelinejava.model;

/**
 * 一个固定时间窗口的聚合结果（OHLC + 成交量）。
 *
 * @param symbol    品种代码
 * @param windowStart 窗口起始 epoch 毫秒（含）
 * @param windowEnd   窗口结束 epoch 毫秒（不含）
 * @param open      窗口内首笔成交价（以业务时间戳最小者为准，时间戳相同取 seq 小者）
 * @param high      窗口内最高价
 * @param low       窗口内最低价
 * @param close     窗口内末笔成交价（以业务时间戳最大者为准，时间戳相同取 seq 大者）
 * @param volume    窗口内成交量之和
 * @param tickCount 窗口内生效报文笔数
 * @param closed    是否已按水位线关闭（关闭后不再接受任何迟到数据）
 */
public record WindowAggregate(
        String symbol,
        long windowStart,
        long windowEnd,
        double open,
        double high,
        double low,
        double close,
        double volume,
        long tickCount,
        boolean closed
) {
}
