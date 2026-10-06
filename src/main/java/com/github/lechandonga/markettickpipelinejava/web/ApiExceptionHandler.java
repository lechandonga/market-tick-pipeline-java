package com.github.lechandonga.markettickpipelinejava.web;

import com.github.lechandonga.markettickpipelinejava.validation.RejectReason;
import com.github.lechandonga.markettickpipelinejava.validation.TickRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理：任何被拒绝的报文都返回带独立 reason 原因码的错误响应，
 * 绝不静默丢弃。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(TickRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleRejected(TickRejectedException ex) {
        RejectReason reason = ex.reason();
        log.warn("[REJECT] reason={} detail={}", reason.code(), ex.getMessage());
        return ResponseEntity.status(reason.httpStatus())
                .body(Map.of(
                        "status", "REJECTED",
                        "reason", reason.code(),
                        "message", ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("[REJECT] reason={} detail={}", RejectReason.MALFORMED_JSON.code(),
                ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "status", "REJECTED",
                        "reason", RejectReason.MALFORMED_JSON.code(),
                        "message", RejectReason.MALFORMED_JSON.message()));
    }
}
