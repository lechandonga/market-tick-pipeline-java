package com.github.lechandonga.markettickpipelinejava.web;

import com.github.lechandonga.markettickpipelinejava.model.IngestResult;
import com.github.lechandonga.markettickpipelinejava.model.RejectReason;
import com.github.lechandonga.markettickpipelinejava.model.WindowAggregate;
import com.github.lechandonga.markettickpipelinejava.service.AggregationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * HTTP 接口：
 *
 * <ul>
 *   <li>POST /api/ticks —— 提交一条行情报文（原始 JSON 作为请求体）。</li>
 *   <li>GET  /api/symbols/{symbol}/windows —— 查询某品种全部窗口聚合。</li>
 *   <li>GET  /api/symbols/{symbol}/windows/at?timestamp=... —— 查询某时间戳所属窗口。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class TickController {

    private final AggregationService aggregationService;

    public TickController(AggregationService aggregationService) {
        this.aggregationService = aggregationService;
    }

    @PostMapping("/ticks")
    public ResponseEntity<?> submit(@RequestBody(required = false) String rawJson) {
        IngestResult result = aggregationService.submit(rawJson);
        if (result.accepted()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        }
        HttpStatus status = switch (RejectReason.valueOf(
                // reason 存的是 code，枚举名与 code 一致
                result.reason())) {
            case DUPLICATE_SEQ -> HttpStatus.CONFLICT;
            case LATE_CLOSED_WINDOW -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.BAD_REQUEST;
        };
        ApiError error = new ApiError(result.reason(), result.message(), null,
                result.symbol(), result.seq());
        return ResponseEntity.status(status).body(Map.of(
                "result", result,
                "error", error));
    }

    @GetMapping("/symbols/{symbol}/windows")
    public List<WindowAggregate> windows(@PathVariable String symbol) {
        return aggregationService.query(symbol);
    }

    @GetMapping("/symbols/{symbol}/windows/at")
    public ResponseEntity<WindowAggregate> windowAt(
            @PathVariable String symbol,
            @RequestParam("timestamp") long timestamp) {
        WindowAggregate aggregate = aggregationService.queryWindowForTs(symbol, timestamp);
        return aggregate == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(aggregate);
    }
}
