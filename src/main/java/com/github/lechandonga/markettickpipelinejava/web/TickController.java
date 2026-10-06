package com.github.lechandonga.markettickpipelinejava.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.lechandonga.markettickpipelinejava.model.SubmitOutcome;
import com.github.lechandonga.markettickpipelinejava.model.Tick;
import com.github.lechandonga.markettickpipelinejava.model.WindowAggregate;
import com.github.lechandonga.markettickpipelinejava.service.TickPipelineService;
import com.github.lechandonga.markettickpipelinejava.validation.TickValidator;
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
 * - POST /api/ticks                 提交一条行情报文；
 * - GET  /api/ticks/{symbol}/windows 查询某品种全部窗口聚合；
 * - GET  /api/ticks/{symbol}/windows/at?timestamp= 查询 timestamp 所属窗口；
 * - GET  /api/ticks/{symbol}/windows/{windowStart} 查询指定起点窗口。
 *
 * 报文校验失败返回 400/422 且带可区分的 reason 原因码；重复与迟到丢弃属于
 * 明确的业务结果，返回 200 并在 body 中以 DUPLICATE / LATE_DROPPED 标记。
 */
@RestController
@RequestMapping("/api/ticks")
public class TickController {

    private final TickPipelineService service;
    private final TickValidator validator;

    public TickController(TickPipelineService service, TickValidator validator) {
        this.service = service;
        this.validator = validator;
    }

    @PostMapping
    public ResponseEntity<SubmitOutcome> submit(@RequestBody(required = false) JsonNode body) {
        JsonNode node = validator.requireObject(body);
        TickValidator.ParsedTick parsed = validator.parse(node);
        Tick tick = new Tick(parsed.symbol(), parsed.seq(), parsed.price(),
                parsed.volume(), parsed.timestamp());
        SubmitOutcome outcome = service.submit(tick);
        return ResponseEntity.ok(outcome);
    }

    @GetMapping("/{symbol}/windows")
    public Map<String, Object> windows(@PathVariable String symbol) {
        List<WindowAggregate> windows = service.query(symbol);
        return Map.of(
                "symbol", symbol,
                "windowSizeMillis", service.windowSizeMillis(),
                "windows", windows);
    }

    @GetMapping("/{symbol}/windows/at")
    public ResponseEntity<WindowAggregate> windowAt(@PathVariable String symbol,
                                                    @RequestParam("timestamp") long timestamp) {
        long size = service.windowSizeMillis();
        long start = Math.floorDiv(timestamp, size) * size;
        WindowAggregate aggregate = service.queryWindow(symbol, start);
        return aggregate == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(aggregate);
    }

    @GetMapping("/{symbol}/windows/{windowStart}")
    public ResponseEntity<WindowAggregate> window(@PathVariable String symbol,
                                                  @PathVariable long windowStart) {
        WindowAggregate aggregate = service.queryWindow(symbol, windowStart);
        return aggregate == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(aggregate);
    }
}
