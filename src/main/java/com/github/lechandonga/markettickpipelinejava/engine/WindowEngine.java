package com.github.lechandonga.markettickpipelinejava.engine;

import com.github.lechandonga.markettickpipelinejava.model.Tick;
import com.github.lechandonga.markettickpipelinejava.model.WindowAggregate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 单品种固定时间窗口 OHLCV 聚合引擎。
 *
 * 关键语义：
 * - 窗口为左闭右开 [k*size, (k+1)*size)；
 * - watermark = 已观察到的最大事件时间（timestamp）。窗口 [start,end) 在
 *   watermark >= end 时判定为已关闭；
 * - 迟到报文若目标窗口仍开启则照常计入（支持乱序）；窗口已关闭则明确丢弃，
 *   绝不改写已关闭窗口；
 * - open/close 由（timestamp, seq）最小/最大的报文决定，因此与到达顺序无关，
 *   乱序到达也能得到确定性结果；
 * - 所有方法均为 synchronized：同品种的判定与状态变更原子完成，并发提交
 *   的结果与某种串行顺序完全一致。
 */
public class WindowEngine {

    private final long windowSizeMillis;

    private final TreeMap<Long, MutableWindow> windows = new TreeMap<>();
    private final java.util.Set<Long> seenSeqs = new java.util.HashSet<>();
    private long watermark = Long.MIN_VALUE;

    public WindowEngine(long windowSizeMillis) {
        if (windowSizeMillis <= 0) {
            throw new IllegalArgumentException("windowSizeMillis must be positive");
        }
        this.windowSizeMillis = windowSizeMillis;
    }

    public long windowSizeMillis() {
        return windowSizeMillis;
    }

    private long windowStartOf(long timestamp) {
        return Math.floorDiv(timestamp, windowSizeMillis) * windowSizeMillis;
    }

    /**
     * 在线提交流程的判定结果。
     */
    public sealed interface Decision permits Decision.Accept, Decision.Duplicate, Decision.LateDrop {
        record Accept(long windowStart, long windowEnd) implements Decision {
        }

        record Duplicate() implements Decision {
        }

        record LateDrop(long windowStart, long windowEnd) implements Decision {
        }
    }

    /**
     * 判定一条报文：重复 / 迟到丢弃 / 接受。判定本身不修改窗口（接受与丢弃由
     * 调用方在日志落盘成功后调用 commit* 生效），但 watermark 的推进也在
     * commit 阶段完成，保证“先持久化、后生效”。
     */
    public synchronized Decision evaluate(Tick tick) {
        if (seenSeqs.contains(tick.seq())) {
            return new Decision.Duplicate();
        }
        long start = windowStartOf(tick.timestamp());
        long end = start + windowSizeMillis;
        if (watermark >= end) {
            return new Decision.LateDrop(start, end);
        }
        return new Decision.Accept(start, end);
    }

    /**
     * 接受报文生效：计入窗口并推进 watermark。
     */
    public synchronized void commitAccepted(Tick tick, long windowStart) {
        seenSeqs.add(tick.seq());
        MutableWindow w = windows.computeIfAbsent(windowStart,
                k -> new MutableWindow(windowStart, windowStart + windowSizeMillis));
        w.add(tick);
        if (tick.timestamp() > watermark) {
            watermark = tick.timestamp();
        }
    }

    /**
     * 迟到丢弃生效：仅登记序号（重复到达仍可识别）并推进 watermark，
     * 不触碰任何窗口。
     */
    public synchronized void commitLateDrop(Tick tick) {
        seenSeqs.add(tick.seq());
        if (tick.timestamp() > watermark) {
            watermark = tick.timestamp();
        }
    }

    /**
     * 重启恢复：重放一条当时被接受的报文。按日志原始顺序重放会重建出与
     * 当时完全一致的 watermark，因此不需要重新做迟到判定。
     */
    public synchronized void recoverAccepted(Tick tick) {
        long start = windowStartOf(tick.timestamp());
        commitAccepted(tick, start);
    }

    /**
     * 重启恢复：重放一条当时被判定迟到丢弃的报文（只登记序号与 watermark）。
     */
    public synchronized void recoverLateDrop(Tick tick) {
        commitLateDrop(tick);
    }

    public synchronized boolean isClosed(long windowEnd) {
        return watermark >= windowEnd;
    }

    public synchronized long watermark() {
        return watermark;
    }

    /**
     * 导出某品种全部窗口快照（按窗口起点升序）。
     */
    public synchronized List<WindowAggregate> snapshot(String symbol) {
        List<WindowAggregate> result = new ArrayList<>(windows.size());
        for (MutableWindow w : windows.values()) {
            result.add(w.toAggregate(symbol, watermark >= w.windowEnd));
        }
        return result;
    }

    /**
     * 单个窗口的可变聚合状态。
     */
    private static final class MutableWindow {
        private final long windowStart;
        private final long windowEnd;

        private double open;
        private double high = Double.NEGATIVE_INFINITY;
        private double low = Double.POSITIVE_INFINITY;
        private double close;
        private double volume;
        private long tickCount;

        private java.time.Instant openKeyInstant = java.time.Instant.MAX;
        private java.time.Instant closeKeyInstant = java.time.Instant.MIN;

        private MutableWindow(long windowStart, long windowEnd) {
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
        }

        private static java.time.Instant orderingKey(Tick tick) {
            // 同一毫秒内用 seq 打破平局：seq 越小越早
            return java.time.Instant.ofEpochMilli(tick.timestamp())
                    .plusNanos((tick.seq() - 1) % 1_000_000L);
        }

        void add(Tick tick) {
            java.time.Instant key = orderingKey(tick);
            if (key.isBefore(openKeyInstant)) {
                openKeyInstant = key;
                open = tick.price();
            }
            if (key.isAfter(closeKeyInstant)) {
                closeKeyInstant = key;
                close = tick.price();
            }
 high = Math.max(high, tick.price());
            low = Math.min(low, tick.price());
            volume += tick.volume();
            tickCount++;
        }

        WindowAggregate toAggregate(String symbol, boolean closed) {
            return new WindowAggregate(symbol, windowStart, windowEnd,
                    open, high, low, close, volume, tickCount, closed);
        }
    }
}
