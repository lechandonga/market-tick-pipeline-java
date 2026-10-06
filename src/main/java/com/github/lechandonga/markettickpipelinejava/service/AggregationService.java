package com.github.lechandonga.markettickpipelinejava.service;

import com.github.lechandonga.markettickpipelinejava.exception.TickRejectedException;
import com.github.lechandonga.markettickpipelinejava.model.IngestResult;
import com.github.lechandonga.markettickpipelinejava.model.RejectReason;
import com.github.lechandonga.markettickpipelinejava.model.Tick;
import com.github.lechandonga.markettickpipelinejava.model.WindowAggregate;
import com.github.lechandonga.markettickpipelinejava.store.TickLogStore;
import com.github.lechandonga.markettickpipelinejava.validation.TickValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 行情接入与窗口聚合核心服务。
 *
 * <p><b>并发模型</b>：按品种分片，每个品种持有一把锁
 * （{@link SymbolAggregateState} 实例本身作为 monitor）。同品种提交完全
 * 串行化，多线程并发提交的结果与按到达顺序串行提交完全一致；不同品种互不阻塞。
 *
 * <p><b>持久化原子语义</b>："原始报文强制刷盘 + 内存聚合生效"在同一把
 * 品种锁内完成。先落盘成功才修改内存；落盘失败则内存不变并抛出异常。
 * 只有真正写入原始日志的报文才参与聚合，保证重启后结果可复现。
 */
@Service
public class AggregationService {

    private static final Logger log = LoggerFactory.getLogger(AggregationService.class);

    private final TickValidator validator;
    private final TickLogStore logStore;
    private final long windowSizeMs;
    private final long allowedLatenessMs;

    private final ConcurrentHashMap<String, SymbolAggregateState> states = new ConcurrentHashMap<>();

    public AggregationService(
            TickValidator validator,
            TickLogStore logStore,
            @Value("${pipeline.window-size-ms:60000}") long windowSizeMs,
            @Value("${pipeline.allowed-lateness-ms:5000}") long allowedLatenessMs) {
        this.validator = validator;
        this.logStore = logStore;
        this.windowSizeMs = windowSizeMs;
        this.allowedLatenessMs = allowedLatenessMs;
    }

    private SymbolAggregateState stateFor(String symbol) {
        return states.computeIfAbsent(symbol,
                k -> new SymbolAggregateState(k, windowSizeMs, allowedLatenessMs));
    }

    /**
     * 提交一条原始行情报文（JSON 文本）。
     * 任何非法输入都返回带可区分 reason 的结果，并写入 rejected 审计日志，不静默丢弃。
     */
    public IngestResult submit(String rawJson) {
        Tick tick;
        String raw;
        try {
            TickValidator.Validated validated = validator.parse(rawJson);
            tick = validated.tick();
            raw = validated.raw();
        } catch (TickRejectedException e) {
            auditReject(e);
            return IngestResult.rejected(e.reason(), e.reason().defaultMessage(), e.symbol(), e.seq());
        }

        SymbolAggregateState state = stateFor(tick.symbol());
        synchronized (state) {
            if (state.isDuplicate(tick.seq())) {
                log.debug("重复报文被拒绝 symbol={} seq={}", tick.symbol(), tick.seq());
                auditReject(RejectReason.DUPLICATE_SEQ, "seq", raw, tick.symbol(), tick.seq());
                return IngestResult.rejected(RejectReason.DUPLICATE_SEQ,
                        RejectReason.DUPLICATE_SEQ.defaultMessage(), tick.symbol(), tick.seq());
            }

            long wStart = state.windowStartOf(tick.ts());
            if (state.watermark() >= wStart + windowSizeMs) {
                // 所属窗口已按水位线永久关闭：迟到数据明确丢弃，不聚合、不落原始日志。
                state.markSeen(tick.seq());
                auditReject(RejectReason.LATE_CLOSED_WINDOW, "timestamp", raw,
                        tick.symbol(), tick.seq());
                return IngestResult.rejected(RejectReason.LATE_CLOSED_WINDOW,
                        RejectReason.LATE_CLOSED_WINDOW.defaultMessage(), tick.symbol(), tick.seq());
            }

            // 先持久化（force 刷盘），成功后内存才生效。
            logStore.appendTick(tick);
            long acceptedWindow = state.accept(tick);
            if (acceptedWindow < 0) {
                state.markSeen(tick.seq());
                auditReject(RejectReason.LATE_CLOSED_WINDOW, "timestamp", raw,
                        tick.symbol(), tick.seq());
                return IngestResult.rejected(RejectReason.LATE_CLOSED_WINDOW,
                        RejectReason.LATE_CLOSED_WINDOW.defaultMessage(), tick.symbol(), tick.seq());
            }
            log.debug("报文生效 symbol={} seq={} window={}", tick.symbol(), tick.seq(), acceptedWindow);
            return IngestResult.accepted(tick, acceptedWindow);
        }
    }

    /**
     * 重启恢复：从已持久化的原始 tick 重建状态，不再写盘。
     * 原始日志只含生效报文，恢复结果与重启前完全一致。
     */
    public void replay(Tick tick) {
        SymbolAggregateState state = stateFor(tick.symbol());
        synchronized (state) {
            if (state.isDuplicate(tick.seq())) {
                log.warn("恢复时遇到重复序号，跳过 symbol={} seq={}", tick.symbol(), tick.seq());
                return;
            }
            long wStart = state.accept(tick);
            if (wStart < 0) {
                log.warn("恢复时 tick 落在已关闭窗口，跳过 symbol={} seq={}", tick.symbol(), tick.seq());
            }
        }
    }

    /** 查询某品种全部窗口聚合（窗口起始时间升序），品种未知返回空列表。 */
    public List<WindowAggregate> query(String symbol) {
        SymbolAggregateState state = states.get(symbol);
        if (state == null) {
            return List.of();
        }
        synchronized (state) {
            return state.snapshot();
        }
    }

    /** 查询某品种指定窗口起始的聚合，不存在返回 null。 */
    public WindowAggregate queryWindow(String symbol, long windowStart) {
        SymbolAggregateState state = states.get(symbol);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            return state.snapshotWindow(windowStart);
        }
    }

    /** 查询某品种、某业务时间戳所属窗口的聚合，不存在返回 null。 */
    public WindowAggregate queryWindowForTs(String symbol, long ts) {
        SymbolAggregateState state = states.get(symbol);
        if (state == null) {
            return null;
        }
        synchronized (state) {
            return state.snapshotWindowForTs(ts);
        }
    }

    public long windowSizeMs() {
        return windowSizeMs;
    }

    public long allowedLatenessMs() {
        return allowedLatenessMs;
    }

    private void auditReject(TickRejectedException e) {
        logStore.appendRejected(com.github.lechandonga.markettickpipelinejava.model.RejectedTick.of(
                e.reason(), e.field(), e.raw(), e.symbol(), e.seq()));
    }

    private void auditReject(RejectReason reason, String field, String raw, String symbol, Long seq) {
        logStore.appendRejected(com.github.lechandonga.markettickpipelinejava.model.RejectedTick.of(
                reason, field, raw, symbol, seq));
    }
}
