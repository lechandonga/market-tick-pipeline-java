package com.github.lechandonga.markettickpipelinejava.storage;

import com.github.lechandonga.markettickpipelinejava.model.Tick;

/**
 * 本地追加日志中的一条记录。
 * 只有两类记录：
 * TICK      —— 已接受并计入（或未来将计入）窗口的原始报文；
 * LATE_DROP —— 到达时目标窗口已关闭、被明确丢弃的报文（同样持久化，
 *               使重启恢复后丢弃决策可复现，且重复序号仍能识别）。
 */
public record JournalRecord(Type type, Tick tick,
                            Long windowStart, Long windowEnd, long ingestTime) {

    public enum Type {
        TICK, LATE_DROP
    }

    public static JournalRecord tick(Tick tick, long ingestTime) {
        return new JournalRecord(Type.TICK, tick, null, null, ingestTime);
    }

    public static JournalRecord lateDrop(Tick tick, long windowStart, long windowEnd, long ingestTime) {
        return new JournalRecord(Type.LATE_DROP, tick, windowStart, windowEnd, ingestTime);
    }
}
