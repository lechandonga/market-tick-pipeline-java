package com.github.lechandonga.markettickpipelinejava.validation;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 行情报文校验器：字段缺失与数值异常分开给出原因码。
 *
 * 校验规则（按固定顺序短路，保证原因稳定可区分）：
 * 1. 字段缺失：symbol / seq / price / volume / timestamp 各自独立原因；
 * 2. symbol 非空白串；seq 为正整数；
 * 3. price 为有限正数；volume 为有限非负数；
 * 4. timestamp 为正数，且不晚于“当前时间 + 未来容忍度（默认 60s）”，不早于 1970-01-02
 *    （既防止脏时间戳污染窗口，也保证重启恢复时不会因时钟漂移误判）。
 */
@Component
public class TickValidator {

    private final long futureToleranceMillis;

    public TickValidator() {
        this(Duration.ofSeconds(60).toMillis());
    }

    TickValidator(long futureToleranceMillis) {
        this.futureToleranceMillis = futureToleranceMillis;
    }

    public JsonNode requireObject(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new TickRejectedException(RejectReason.MALFORMED_JSON, "根节点必须是 JSON 对象");
        }
        return body;
    }

    /**
     * 从原始 JSON 中取出并校验字段。对 null / 显式缺失 / 空串分别给出可区分原因。
     */
    public ParsedTick parse(JsonNode node) {
        JsonNode symbolNode = node.get("symbol");
        if (symbolNode == null || symbolNode.isNull()) {
            throw new TickRejectedException(RejectReason.MISSING_SYMBOL);
        }
        JsonNode seqNode = node.get("seq");
        if (seqNode == null || seqNode.isNull()) {
            throw new TickRejectedException(RejectReason.MISSING_SEQ);
        }
        JsonNode priceNode = node.get("price");
        if (priceNode == null || priceNode.isNull()) {
            throw new TickRejectedException(RejectReason.MISSING_PRICE);
        }
        JsonNode volumeNode = node.get("volume");
        if (volumeNode == null || volumeNode.isNull()) {
            throw new TickRejectedException(RejectReason.MISSING_VOLUME);
        }
        JsonNode tsNode = node.get("timestamp");
        if (tsNode == null || tsNode.isNull()) {
            throw new TickRejectedException(RejectReason.MISSING_TIMESTAMP);
        }

        if (!symbolNode.isTextual() || symbolNode.asText().isBlank()) {
            throw new TickRejectedException(RejectReason.BLANK_SYMBOL);
        }
        if (!seqNode.isIntegralNumber() || seqNode.asLong() <= 0) {
            throw new TickRejectedException(RejectReason.SEQ_OUT_OF_RANGE,
                    "seq=" + seqNode);
        }
        if (!priceNode.isNumber() || !Double.isFinite(priceNode.asDouble()) || priceNode.asDouble() <= 0) {
            throw new TickRejectedException(RejectReason.INVALID_PRICE,
                    "price=" + priceNode);
        }
        if (!volumeNode.isNumber() || !Double.isFinite(volumeNode.asDouble()) || volumeNode.asDouble() < 0) {
            throw new TickRejectedException(RejectReason.INVALID_VOLUME,
                    "volume=" + volumeNode);
        }
        if (!tsNode.isIntegralNumber()) {
            throw new TickRejectedException(RejectReason.INVALID_TIMESTAMP,
                    "timestamp 必须为 epoch 毫秒整数");
        }
        long ts = tsNode.asLong();
        long now = System.currentTimeMillis();
        if (ts <= 0 || ts < Duration.ofDays(1).toMillis() || ts > now + futureToleranceMillis) {
            throw new TickRejectedException(RejectReason.INVALID_TIMESTAMP,
                    "timestamp=" + ts);
        }

        return new ParsedTick(symbolNode.asText().trim(), seqNode.asLong(),
                priceNode.asDouble(), volumeNode.asDouble(), ts);
    }

    /**
     * 校验通过后的不可变中间结果。
     */
    public record ParsedTick(String symbol, long seq, double price, double volume, long timestamp) {
    }
}
