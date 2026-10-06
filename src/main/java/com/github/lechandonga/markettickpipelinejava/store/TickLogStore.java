package com.github.lechandonga.markettickpipelinejava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.markettickpipelinejava.model.RejectedTick;
import com.github.lechandonga.markettickpipelinejava.model.Tick;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 本地文件持久化（不依赖任何外部中间件）。
 *
 * <ul>
 *   <li>{@code ticks/<symbol>.jsonl}：每个品种一个 append-only 原始报文日志，
 *       只记录生效报文，是重启恢复的唯一事实来源；每行一个 JSON。</li>
 *   <li>{@code rejected.jsonl}：所有被拒绝报文（含原因），审计用途，
 *       保证异常报文不会被静默丢弃。</li>
 * </ul>
 *
 * <p>写入采用 append + {@code flush} + {@code force(true)}，进程崩溃时
 * 已确认写入的报文不会丢失。同品种写入由 {@code fileLocks} 串行化，
 * 与聚合服务的品种锁配合，保证"持久化成功"与"内存生效"原子可见。
 */
@Component
public class TickLogStore {

    private static final Logger log = LoggerFactory.getLogger(TickLogStore.class);

    private final Path dataDir;
    private final Path ticksDir;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Object> fileLocks = new ConcurrentHashMap<>();

    public TickLogStore(@Value("${pipeline.data-dir:./data}") String dataDir, ObjectMapper objectMapper) {
        this.dataDir = Path.of(dataDir).toAbsolutePath().normalize();
        this.ticksDir = this.dataDir.resolve("ticks");
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(ticksDir);
        log.info("TickLogStore 初始化完成，数据目录: {}", dataDir);
    }

    /**
     * 把一条生效报文追加到该品种的原始报文日志（强制刷盘）。
     */
    public void appendTick(Tick tick) {
        Object lock = fileLocks.computeIfAbsent(tick.symbol(), k -> new Object());
        synchronized (lock) {
            try {
                appendLine(resolveTickFile(tick.symbol()), objectMapper.writeValueAsString(tick));
            } catch (IOException e) {
                throw new UncheckedIOException("持久化行情报文失败: " + tick, e);
            }
        }
    }

    /**
     * 把一条拒绝记录追加到全局审计日志（强制刷盘）。
     */
    public void appendRejected(RejectedTick rejected) {
        try {
            appendLine(dataDir.resolve("rejected.jsonl"), objectMapper.writeValueAsString(rejected));
        } catch (IOException e) {
            throw new UncheckedIOException("持久化拒绝记录失败", e);
        }
    }

    /**
     * 启动恢复：按品种读取全部已持久化的原始报文。
     * 返回结果中每个品种的报文保持文件内先后顺序（即当时的到达顺序）。
     */
    public List<RecoveredSymbol> recoverAll() throws IOException {
        List<RecoveredSymbol> result = new ArrayList<>();
        if (!Files.isDirectory(ticksDir)) {
            return result;
        }
        try (Stream<Path> files = Files.list(ticksDir)) {
            List<Path> paths = files
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .sorted()
                    .toList();
            for (Path file : paths) {
                String symbol = decodeFileName(file.getFileName().toString());
                List<Tick> ticks = recoverSymbol(file);
                result.add(new RecoveredSymbol(symbol, ticks));
            }
        }
        return result;
    }

    private List<Tick> recoverSymbol(Path file) throws IOException {
        List<Tick> ticks = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            ticks.add(objectMapper.readValue(line, Tick.class));
        }
        return ticks;
    }

    private void appendLine(Path file, String jsonLine) throws IOException {
        // rws 等价语义：open with append，每次 force。用 BufferedWriter 包一层
        // 减少系统调用，但每条报文都显式 flush + force。
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file.toFile(), true);
             BufferedWriter writer = new BufferedWriter(
                     new java.io.OutputStreamWriter(fos, StandardCharsets.UTF_8))) {
            writer.write(jsonLine);
            writer.newLine();
            writer.flush();
            fos.getFD().sync();
        }
    }

    private Path resolveTickFile(String symbol) {
        return ticksDir.resolve(encodeFileName(symbol) + ".jsonl");
    }

    /**
     * 品种名可能含路径分隔符等字符，做 URL 风格转义，保证一品种一文件且不越出数据目录。
     */
    static String encodeFileName(String symbol) {
        StringBuilder sb = new StringBuilder(symbol.length() + 8);
        for (int i = 0; i < symbol.length(); i++) {
            char c = symbol.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            } else {
                sb.append('%');
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    sb.append(String.format("%02X", b));
                }
            }
        }
        return sb.toString();
    }

    static String decodeFileName(String fileName) {
        String encoded = fileName.substring(0, fileName.length() - ".jsonl".length());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '%' && i + 2 < encoded.length()) {
                out.write(Integer.parseInt(encoded.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                out.write(c);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * 恢复出的单品种报文集合，保持持久化时的先后顺序。
     */
    public record RecoveredSymbol(String symbol, List<Tick> ticks) {
    }

}
