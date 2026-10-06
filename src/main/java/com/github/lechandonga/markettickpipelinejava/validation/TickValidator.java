package com.github.lechandonga.markettickpipelinejava.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.markettickpipelinejava.exception.TickRejectedException;
import com.github.lechandonga.markettickpipelinejava.model.RejectReason;
import com.github.lechandonga.markettickpipelinejava.model.Tick;
import org.springframework.stereotype.Component;

/**
 * 行情报文校验：把原始 JSON 文本解析为 {@link Tick}。
 *
 * <p>校验顺序固定，保证每类问题都有独立、可区分的 reasonCode：
 * 报文语法 → 根类型 → 必填字段存在性 → 字段类型 → 字段取值。
 * 取值阶段先尽量提取 symbol / seq，便于审计日志定位问题报文。
 */
@Component
public class TickValidator {

    private final ObjectMapper objectMapper;

    public TickValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析并校验原始报文。任何不合法情形都抛出
     * {@link TickRejectedException}，绝不静默返回。
     */
    public Validated parse(String raw) {
        JsonNode root;
        try {
            if (raw == null || raw.isBlank()) {
                throw new TickRejectedException(RejectReason.MALFORMED_JSON, null, raw, null, null);
            }
            root = objectMapper.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new TickRejectedException(RejectReason.MALFORMED_JSON, null, raw, null, null);
        }
        if (root == null || !root.isObject()) {
            throw new TickRejectedException(RejectReason.NOT_AN_OBJECT, null, raw, null, null);
        }

        requireField(root, "symbol");
        requireField(root, "seq");
        requireField(root, "price");
        requireField(root, "quantity");
        requireField(root, "timestamp");

        // 先提取用于定位的 symbol / seq（取值稍后再严格校验）。
        String symbol = root.path("symbol").asText(null);
        Long seq = root.path("seq").canConvertToLong() ? root.path("seq").asLong() : null;

        if (!root.get("symbol").isTextual()) {
            reject(RejectReason.INVALID_TYPE, "symbol", raw, symbol, seq);
        }
        if (symbol != null && symbol.isBlank()) {
            reject(RejectReason.BLANK_SYMBOL, "symbol", raw, symbol, seq);
        }
        if (!root.get("seq").isIntegralNumber()) {
            reject(RejectReason.INVALID_TYPE, "seq", raw, symbol, seq);
        }
        if (seq != null && seq < 1) {
            reject(RejectReason.INVALID_SEQ, "seq", raw, symbol, seq);
        }
        if (!root.get("price").isNumber()) {
            reject(RejectReason.INVALID_TYPE, "price", raw, symbol, seq);
        }
        double price = root.get("price").asDouble();
        if (!Double.isFinite(price) || price <= 0) {
            reject(RejectReason.INVALID_PRICE, "price", raw, symbol, seq);
        }
        if (!root.get("quantity").isNumber()) {
            reject(RejectReason.INVALID_TYPE, "quantity", raw, symbol, seq);
        }
        double quantity = root.get("quantity").asDouble();
        if (!Double.isFinite(quantity) || quantity <= 0) {
            reject(RejectReason.INVALID_QUANTITY, "quantity", raw, symbol, seq);
        }
        if (!root.get("timestamp").isIntegralNumber()) {
            reject(RejectReason.INVALID_TYPE, "timestamp", raw, symbol, seq);
        }
        long ts = root.get("timestamp").asLong();
        if (ts < 0) {
            reject(RejectReason.INVALID_TIMESTAMP, "timestamp", raw, symbol, seq);
        }

        return new Validated(new Tick(symbol, seq, price, quantity, ts), raw);
    }

    private static void requireField(JsonNode root, String name) {
        JsonNode node = root.get(name);
        if (node == null || node.isNull()) {
            String rawSymbol = root.path("symbol").asText(null);
            Long rawSeq = root.path("seq").canConvertToLong() ? root.path("seq").asLong() : null;
            reject(RejectReason.MISSING_FIELD, name, null, rawSymbol, rawSeq);
        }
    }

    private static void reject(RejectReason reason, String field, String raw, String symbol, Long seq) {
        throw new TickRejectedException(reason, field, raw, symbol, seq);
    }

    /**
     * 校验成功的产物：合法 tick 加原始报文文本（用于原始报文持久化）。
     */
    public record Validated(Tick tick, String raw) {
    }
}
