package com.github.lechandonga.markettickpipelinejava.service;

import com.github.lechandonga.markettickpipelinejava.engine.WindowEngine;
import com.github.lechandonga.markettickpipelinejava.model.SubmitOutcome;
import com.github.lechandonga.markettickpipelinejava.model.Tick;
import com.github.lechandonga.markettickpipelinejava.model.WindowAggregate;
import com.github.lechandonga.markettickpipelinejava.storage.JournalRecord;
import com.github.lechandonga.markettickpipelinejava.storage.TickJournal;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 行情接入与窗口聚合的对外服务。
 *
 * 并发模型：每个品种一把锁（{@link #locks}），同一品种的“去重判定 → 迟到判定
 * → 日志落盘 → 内存生效”全程在该锁内原子完成；不同品种互不阻塞。
 * 因此多线程并发提交同一品种时，结果与按某一次序串行提交完全一致。
 *
 * 持久化：被接受与被判定迟到丢弃的原始报文都以到达顺序追加到本地日志，
 * 提交返回前已强制落盘；重启时严格按日志顺序重放，窗口结果与重启前一致。
 */
@Service
public class TickPipelineService {

    private static final Logger log = LoggerFactory.getLogger(TickPipelineService.class);

    private final TickJournal journal;
    private final long windowSizeMillis;

    private final Map<String, WindowEngine> engines = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public TickPipelineService(TickJournal journal,
                               @Value("${pipeline.window-size-millis:60000}") long windowSizeMillis) {
        this.journal = journal;
        this.windowSizeMillis = windowSizeMillis;
    }

    @PostConstruct
    public void recover() {
        List<JournalRecord> records = journal.replayAll();
        int accepted = 0;
        int dropped = 0;
        for (JournalRecord r : records) {
            WindowEngine engine = engines.computeIfAbsent(r.tick().symbol(),
                    s -> new WindowEngine(windowSizeMillis));
            if (r.type() == JournalRecord.Type.TICK) {
                engine.recoverAccepted(r.tick());
                accepted++;
            } else {
                engine.recoverLateDrop(r.tick());
                dropped++;
            }
        }
        log.info("[RECOVERY] 启动恢复完成：重放日志记录 {} 条（接受计入 {}，迟到丢弃 {}），品种数 {}",
                records.size(), accepted, dropped, engines.size());
    }

    /**
     * 提交一条已通过格式校验的行情报文。
     */
    public SubmitOutcome submit(Tick tick) {
        Object lock = locks.computeIfAbsent(tick.symbol(), s -> new Object());
        synchronized (lock) {
            WindowEngine engine = engines.computeIfAbsent(tick.symbol(),
                    s -> new WindowEngine(windowSizeMillis));

            WindowEngine.Decision decision = engine.evaluate(tick);
            long now = System.currentTimeMillis();

            switch (decision) {
                case WindowEngine.Decision.Duplicate d -> {
                    log.info("[SUBMIT] DUPLICATE symbol={} seq={} 重复报文只生效一次",
                            tick.symbol(), tick.seq());
                    return SubmitOutcome.duplicate(tick.seq());
                }
                case WindowEngine.Decision.LateDrop d -> {
                    // 先持久化丢弃决策，再生效；重放时丢弃行为可复现。
                    journal.append(JournalRecord.lateDrop(tick, d.windowStart(), d.windowEnd(), now));
                    engine.commitLateDrop(tick);
                    log.warn("[SUBMIT] LATE_DROPPED symbol={} seq={} ts={} 窗口[{},{})已关闭，报文丢弃",
                            tick.symbol(), tick.seq(), tick.timestamp(),
                            d.windowStart(), d.windowEnd());
                    return SubmitOutcome.lateDropped(d.windowStart(), d.windowEnd());
                }
                case WindowEngine.Decision.Accept a -> {
                    journal.append(JournalRecord.tick(tick, now));
                    engine.commitAccepted(tick, a.windowStart());
                    log.info("[SUBMIT] ACCEPTED symbol={} seq={} ts={} price={} volume={} -> 窗口[{},{}) closed={}",
                            tick.symbol(), tick.seq(), tick.timestamp(), tick.price(), tick.volume(),
                            a.windowStart(), a.windowEnd(), engine.isClosed(a.windowEnd()));
                    return SubmitOutcome.accepted(a.windowStart(), a.windowEnd());
                }
            }
        }
    }

    /**
     * 查询某品种的窗口聚合结果（窗口起点升序）。
     */
    public List<WindowAggregate> query(String symbol) {
        WindowEngine engine = engines.get(symbol);
        return engine == null ? List.of() : engine.snapshot(symbol);
    }

    /**
     * 查询某品种单个窗口；不存在返回 null。
     */
    public WindowAggregate queryWindow(String symbol, long windowStart) {
        return query(symbol).stream()
                .filter(w -> w.windowStart() == windowStart)
                .findFirst()
                .orElse(null);
    }

    public long windowSizeMillis() {
        return windowSizeMillis;
    }
}
