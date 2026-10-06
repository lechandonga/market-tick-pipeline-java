package com.github.lechandonga.markettickpipelinejava.service;

import com.github.lechandonga.markettickpipelinejava.model.Tick;
import com.github.lechandonga.markettickpipelinejava.model.WindowAggregate;

/**
 * 单个固定时间窗口的可变聚合状态。
 *
 * <p>乱序安全：open/close 不按到达顺序，而按业务时间戳判定；
 * 同一时间戳取 seq 更小（open）/ 更大（close）者。因此任意到达顺序
 * 都得到与串行一致、且与时间序一致的 OHLC。
 */
final class WindowState {

    private final String symbol;
    private final long windowStart;
    private final long windowEnd;

    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;
    private long tickCount;

    private long openTs = Long.MAX_VALUE;
    private long openSeq = Long.MAX_VALUE;
    private long closeTs = Long.MIN_VALUE;
    private long closeSeq = Long.MIN_VALUE;

    private boolean closed;

    WindowState(String symbol, long windowStart, long windowEnd) {
        this.symbol = symbol;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
    }

    /**
     * 把一笔 tick 合并进窗口。调用方必须保证窗口尚未关闭且该 tick 未重复生效。
     */
    void apply(Tick tick) {
        if (tickCount == 0) {
            open = high = low = close = tick.price();
            openTs = closeTs = tick.ts();
            openSeq = closeSeq = tick.seq();
        } else {
            high = Math.max(high, tick.price());
            low = Math.min(low, tick.price());
            if (isEarlierOpen(tick)) {
                open = tick.price();
                openTs = tick.ts();
                openSeq = tick.seq();
            }
            if (isLaterClose(tick)) {
                close = tick.price();
                closeTs = tick.ts();
                closeSeq = tick.seq();
            }
        }
        volume += tick.quantity();
        tickCount++;
    }

    private boolean isEarlierOpen(Tick tick) {
        return tick.ts() < openTs || (tick.ts() == openTs && tick.seq() < openSeq);
    }

    private boolean isLaterClose(Tick tick) {
        return tick.ts() > closeTs || (tick.ts() == closeTs && tick.seq() > closeSeq);
    }

    void markClosed() {
        this.closed = true;
    }

    boolean isClosed() {
        return closed;
    }

    long windowStart() {
        return windowStart;
    }

    WindowAggregate toAggregate() {
        return new WindowAggregate(symbol, windowStart, windowEnd,
                open, high, low, close, volume, tickCount, closed);
    }
}
