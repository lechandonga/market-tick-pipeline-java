package com.github.lechandonga.markettickpipelinejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.markettickpipelinejava.validation.RejectReason;
import com.github.lechandonga.markettickpipelinejava.validation.TickRejectedException;
import com.github.lechandonga.markettickpipelinejava.validation.TickValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 业务一：行情接入与校验。
 * 覆盖：字段缺失（每种字段独立原因）、空 symbol、异常价格/数量/时间戳、序号非法。
 */
@DisplayName("业务一：报文校验——每种拒绝都有可区分原因")
class ValidationTest {

    private static final Logger log = LoggerFactory.getLogger(ValidationTest.class);

    private final TickValidator validator = new TickValidator();
    private final ObjectMapper mapper = new ObjectMapper();
    private final long ts = System.currentTimeMillis() - 60_000L;

    private String validJsonBut(String field, Object value) {
        String symbol = "\"AAA\"";
        String seq = "1";
        String price = "100.5";
        String volume = "10";
        String timestamp = String.valueOf(ts);
        return switch (field) {
            case "symbol" -> "{\"symbol\":%s,\"seq\":%s,\"price\":%s,\"volume\":%s,\"timestamp\":%s}"
                    .formatted(value, seq, price, volume, timestamp);
            case "seq" -> "{\"symbol\":%s,\"seq\":%s,\"price\":%s,\"volume\":%s,\"timestamp\":%s}"
                    .formatted(symbol, value, price, volume, timestamp);
            case "price" -> "{\"symbol\":%s,\"seq\":%s,\"price\":%s,\"volume\":%s,\"timestamp\":%s}"
                    .formatted(symbol, seq, value, volume, timestamp);
            case "volume" -> "{\"symbol\":%s,\"seq\":%s,\"price\":%s,\"volume\":%s,\"timestamp\":%s}"
                    .formatted(symbol, seq, price, value, timestamp);
            default -> "{\"symbol\":%s,\"seq\":%s,\"price\":%s,\"volume\":%s,\"timestamp\":%s}"
                    .formatted(symbol, seq, price, volume, value);
        };
    }

    private String jsonWithout(String field) {
        String s = "{\"symbol\":\"AAA\",\"seq\":1,\"price\":100.5,\"volume\":10,\"timestamp\":"
                + ts + "}";
        String needle = switch (field) {
            case "symbol" -> "\"symbol\":\"AAA\",";
            case "seq" -> "\"seq\":1,";
            case "price" -> "\"price\":100.5,";
            case "volume" -> "\"volume\":10,";
            default -> "\"timestamp\":" + ts + (field.equals("timestamp") ? "" : "");
        };
        if (field.equals("timestamp")) {
            s = s.replace(",\"timestamp\":" + ts, "");
        } else {
            s = s.replace(needle, "");
        }
        return s;
    }

    private void expect(String json, RejectReason expected) throws Exception {
        TickRejectedException ex = assertThrows(TickRejectedException.class,
                () -> validator.parse(validator.requireObject(mapper.readTree(json))));
        assertEquals(expected, ex.reason(),
                () -> "期望原因 " + expected.code() + " 实际 " + ex.reason().code());
        log.info("[用例] {} 拒绝原因={} 说明={}", expected.name(), ex.reason().code(),
                ex.getMessage());
    }

    @Nested
    @DisplayName("1.1 字段缺失：五个字段各有独立原因，不静默丢弃")
    class MissingFields {
        @Test
        @DisplayName("缺少 symbol -> MISSING_SYMBOL")
        void missingSymbol() throws Exception {
            expect(jsonWithout("symbol"), RejectReason.MISSING_SYMBOL);
        }

        @Test
        @DisplayName("缺少 seq -> MISSING_SEQ")
        void missingSeq() throws Exception {
            expect(jsonWithout("seq"), RejectReason.MISSING_SEQ);
        }

        @Test
        @DisplayName("缺少 price -> MISSING_PRICE")
        void missingPrice() throws Exception {
            expect(jsonWithout("price"), RejectReason.MISSING_PRICE);
        }

        @Test
        @DisplayName("缺少 volume -> MISSING_VOLUME")
        void missingVolume() throws Exception {
            expect(jsonWithout("volume"), RejectReason.MISSING_VOLUME);
        }

        @Test
        @DisplayName("缺少 timestamp -> MISSING_TIMESTAMP")
        void missingTimestamp() throws Exception {
            expect(jsonWithout("timestamp"), RejectReason.MISSING_TIMESTAMP);
        }
    }

    @Nested
    @DisplayName("1.2 数值/格式异常：各自可区分")
    class InvalidValues {
        @Test
        @DisplayName("空 symbol -> BLANK_SYMBOL")
        void blankSymbol() throws Exception {
            expect(validJsonBut("symbol", "\"  \""), RejectReason.BLANK_SYMBOL);
        }

        @Test
        @DisplayName("seq 非正整数 -> SEQ_OUT_OF_RANGE")
        void badSeq() throws Exception {
            expect(validJsonBut("seq", "0"), RejectReason.SEQ_OUT_OF_RANGE);
        }

        @Test
        @DisplayName("price 非正数 -> INVALID_PRICE")
        void nonPositivePrice() throws Exception {
            expect(validJsonBut("price", "-1"), RejectReason.INVALID_PRICE);
        }

        @Test
        @DisplayName("price 非数值类型 -> INVALID_PRICE")
        void nonNumericPrice() {
            TickRejectedException ex = assertThrows(TickRejectedException.class,
                    () -> new TickValidator().parse(new ObjectMapper()
                            .readTree("{\"symbol\":\"AAA\",\"seq\":1,\"price\":\"oops\","
                                    + "\"volume\":1,\"timestamp\":" + ts + "}")));
            assertEquals(RejectReason.INVALID_PRICE, ex.reason());
            log.info("[用例] 非数值价格 拒绝原因={}", ex.reason().code());
        }

        @Test
        @DisplayName("volume 为负 -> INVALID_VOLUME")
        void negativeVolume() throws Exception {
            expect(validJsonBut("volume", "-0.1"), RejectReason.INVALID_VOLUME);
        }

        @Test
        @DisplayName("timestamp 为 0 / 远古时间 -> INVALID_TIMESTAMP")
        void ancientTimestamp() throws Exception {
            expect(validJsonBut("timestamp", "0"), RejectReason.INVALID_TIMESTAMP);
        }

        @Test
        @DisplayName("timestamp 超过未来容忍窗口 -> INVALID_TIMESTAMP")
        void futureTimestamp() throws Exception {
            expect(validJsonBut("timestamp", System.currentTimeMillis() + 3600_000L),
                    RejectReason.INVALID_TIMESTAMP);
        }

        @Test
        @DisplayName("非 JSON 报文 -> MALFORMED_JSON")
        void malformed() {
            TickRejectedException ex = assertThrows(TickRejectedException.class,
                    () -> validator.requireObject(mapper.readTree("[1,2,3]")));
            assertEquals(RejectReason.MALFORMED_JSON, ex.reason());
            log.info("[用例] 非对象 JSON 拒绝原因={}", ex.reason().code());
        }
    }
}
