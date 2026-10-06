package com.github.lechandonga.markettickpipelinejava.service;

import com.github.lechandonga.markettickpipelinejava.model.Tick;
import com.github.lechandonga.markettickpipelinejava.model.WindowAggregate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;

/**
 * 单个品种的全部内存状态：已见序号集合、各窗口聚合、水位线。
 *
 * <p>所有方法只允许在持有该品种锁（{@link AggregationService} 以
 * synchronized(symbolState) 保证）的情况下调用，方法内部不再加锁。
 */
final class SymbolAggregateState {

    private final String symbol;
    private final long windowSizeMs;
    private final long allowedLatenessMs;

    private final HashSet<Long> seenSeqs = new HashSet<>();

    /** key = 窗口起始时间（对齐到 windowSize 的整数倍）。 */
    private final TreeMap<Long, WindowState> windows = new TreeMap<>();

    private long maxObservedTs = Long.MIN_VALUE;

    SymbolAggregateState(String symbol, long windowSizeMs, long allowedLatenessMs) {
        this.symbol = symbol;
        this.windowSizeMs = windowSizeMs;
        this.allowedLatenessMs = allowedLatenessMs;
    }

    long windowStartOf(long ts) {
        return Math.floorDiv(ts, windowSizeMs) * windowSizeMs;
    }

    /**
     * 水位线 = 已观察最大时间戳 - 允许迟到量。
     * 窗口 [w, w+size) 在 watermark >= w+size 时永久关闭。
     */
    long watermark() {
        return maxObservedTs == Long.MIN_VALUE ? Long.MIN_VALUE : maxObservedTs - allowedLatenessMs;
    }

    boolean isDuplicate(long seq) {
        return seenSeqs.contains(seq);
    }

    /**
     * 迟到被丢弃的报文也登记序号：同一序号重复到达时按 DUPLICATE_SEQ 处理，
     * 保证语义稳定、可区分。
     */
    void markSeen(long seq) {
        seenSeqs.add(seq);
    }

    /**
     * 让一个已判定可接受的 tick 生效，推进水位线并关闭到期窗口。
     * 窗口是否关闭只由该 tick 到达之前的水位线决定，tick 自身推进水位线
     * 不影响它自己的归属判定。
     *
     * @return 归属窗口起始时间；-1 表示窗口已关闭（迟到丢弃）
     */
    long accept(Tick tick) {
        // 1) 先按"到达前"水位线关闭到期窗口
        closeAllDueWindows();
        long wStart = windowStartOf(tick.ts());
        WindowState existing = windows.get(wStart);
        if (existing != null && existing.isClosed()) {
            return -1;
        }
        // 无窗口但水位线已越过窗口右端，同样视为已关闭
        long wEnd = wStart + windowSizeMs;
        if (existing == null && watermark() >= wEnd) {
            return -1;
        }

        // 2) 生效：登记序号、推进水位线、聚合
        seenSeqs.add(tick.seq());
        if (tick.ts() > maxObservedTs) {
            maxObservedTs = tick.ts();
        }
        WindowState state = windows.computeIfAbsent(wStart,
                k -> new WindowState(symbol, wStart, wStart + windowSizeMs));
        state.apply(tick);
        closeAllDueWindows();
        return wStart;
    }

    private void closeAllDueWindows() {
        long wm = watermark();
        for (WindowState state : windows.values()) {
            if (!state.isClosed() && state.windowStart() + windowSizeMs <= wm) {
                state.markClosed();
            }
        }
    }

    List<WindowAggregate> snapshot() {
        closeAllDueWindows();
        List<WindowAggregate> result = new ArrayList<>(windows.size());
        for (WindowState state : windows.values()) {
            result.add(state.toAggregate());
        }
        return result;
    }

    WindowAggregate snapshotWindow(long windowStart) {
        closeAllDueWindows();
        WindowState state = windows.get(windowStart);
        return state == null ? null : state.toAggregate();
    }

    WindowAggregate snapshotWindowForTs(long ts) {
        return snapshotWindow(windowStartOf(ts));
    }
}
