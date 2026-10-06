package com.github.lechandonga.markettickpipelinejava.service;

import com.github.lechandonga.markettickpipelinejava.model.Tick;
import com.github.lechandonga.markettickpipelinejava.store.TickLogStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时从本地原始报文日志恢复内存聚合状态。
 *
 * <p>恢复以持久化日志为唯一事实来源：每个品种按文件内顺序（即当时的
 * 生效顺序）重放。窗口关闭水位线、序号去重都按与在线提交相同的规则重建，
 * 因此恢复出的 OHLC/成交量与重启前逐窗口一致。
 */
@Component
@Order(0)
public class RecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RecoveryRunner.class);

    private final TickLogStore logStore;
    private final AggregationService aggregationService;

    public RecoveryRunner(TickLogStore logStore, AggregationService aggregationService) {
        this.logStore = logStore;
        this.aggregationService = aggregationService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<TickLogStore.RecoveredSymbol> recovered = logStore.recoverAll();
        long totalTicks = 0;
        for (TickLogStore.RecoveredSymbol symbolTicks : recovered) {
            for (Tick tick : symbolTicks.ticks()) {
                aggregationService.replay(tick);
                totalTicks++;
            }
            log.info("恢复完成 symbol={} 重放报文数={}", symbolTicks.symbol(), symbolTicks.ticks().size());
        }
        log.info("本地恢复结束：品种数={} 重放报文总数={}", recovered.size(), totalTicks);
    }
}
