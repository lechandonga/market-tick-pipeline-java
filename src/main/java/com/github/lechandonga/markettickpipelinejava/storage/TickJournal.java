package com.github.lechandonga.markettickpipelinejava.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.markettickpipelinejava.model.Tick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于本地文件的只追加日志（无任何外部中间件）。
 *
 * 每行一个 JSON 对象；每次 append 都 flush + fsync，保证已接受/已判定丢弃的
 * 原始报文在进程崩溃后仍然存在，重启可按日志顺序重放，得到与重启前一致的聚合结果。
 *
 * 线程模型：append 方法自身在文件通道上串行化；业务层另外保证“同品种判定 + 落盘”
 * 的原子性，二者叠加保证任意并发下等价于串行提交。
 */
@Component
public class TickJournal {

    private static final Logger log = LoggerFactory.getLogger(TickJournal.class);

    private final Path file;
    private final ObjectMapper objectMapper;
    private final Object fileLock = new Object();

    public TickJournal(@Value("${pipeline.data-dir:./data}") String dataDir,
                       ObjectMapper objectMapper) {
        this.file = Path.of(dataDir, "tick-journal.log");
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) {
            Files.createFile(file);
        }
        log.info("[STORAGE] 本地行情日志就绪: {}", file.toAbsolutePath());
    }

    /**
     * 追加一条记录并强制落盘。
     */
    public void append(JournalRecord record) {
        try {
            byte[] line = (objectMapper.writeValueAsString(new WireRecord(record)) + '\n')
                    .getBytes(StandardCharsets.UTF_8);
            synchronized (fileLock) {
                Files.write(file, line,
                        StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("持久化行情日志失败: " + file, e);
        }
    }

    /**
     * 按写入顺序读回全部记录，用于重启恢复。
     * 若末行因崩溃只写了一半（不是完整 JSON），报错并中止，避免静默丢数据。
     */
    public List<JournalRecord> replayAll() {
        List<JournalRecord> records = new ArrayList<>();
        if (!Files.exists(file)) {
            return records;
        }
        try {
            List<String> lines;
            synchronized (fileLock) {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            }
            for (int i = 0; i < lines.size(); i++) {
                String raw = lines.get(i);
                if (raw.isBlank()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = objectMapper.readTree(raw);
                } catch (IOException e) {
                    throw new UncheckedIOException("日志第 " + (i + 1) + " 行损坏，拒绝静默恢复: " + file, e);
                }
                records.add(fromWire(node));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取行情日志失败: " + file, e);
        }
        return records;
    }

    private JournalRecord fromWire(JsonNode node) {
        String type = node.path("type").asText();
        Tick tick = new Tick(
                node.path("symbol").asText(),
                node.path("seq").asLong(),
                node.path("price").asDouble(),
                node.path("volume").asDouble(),
                node.path("timestamp").asLong());
        long ingestTime = node.path("ingestTime").asLong();
        if ("LATE_DROP".equals(type)) {
            return JournalRecord.lateDrop(tick,
                    node.path("windowStart").asLong(),
                    node.path("windowEnd").asLong(), ingestTime);
        }
        return JournalRecord.tick(tick, ingestTime);
    }

    @PreDestroy
    void close() {
        log.info("[STORAGE] 本地行情日志关闭: {}", file.toAbsolutePath());
    }

    /**
     * 日志行的 JSON 表示。
     */
    public record WireRecord(String type, String symbol, long seq, double price,
                             double volume, long timestamp,
                             Long windowStart, Long windowEnd, long ingestTime) {
        WireRecord(JournalRecord r) {
            this(r.type().name(), r.tick().symbol(), r.tick().seq(), r.tick().price(),
                    r.tick().volume(), r.tick().timestamp(),
                    r.windowStart(), r.windowEnd(), r.ingestTime());
        }
    }
}
