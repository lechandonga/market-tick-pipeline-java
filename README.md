# 本地行情接入与窗口聚合服务

纯本地（无 Kafka / Redis / 数据库等外部中间件）的行情接入服务：接收行情报文、
按品种以序号去重、按固定时间窗口聚合 OHLCV（开高低收 + 成交量），支持乱序与
迟到报文处理；原始报文持久化在本地追加日志中，进程重启后重放恢复，聚合结果
与重启前一致。

技术栈：Java 21、Spring Boot 3.4、Jackson；测试 JUnit 5 + Spring Boot Test。

## 1. HTTP 接口

### 1.1 提交行情

`POST /api/ticks`

请求体：

```json
{ "symbol": "IF2410", "seq": 1, "price": 3501.2, "volume": 2, "timestamp": 1727000000000 }
```

字段：`symbol` 品种代码；`seq` 品种内递增序号（正整数，去重键）；`price` 价格；
`volume` 成交量；`timestamp` 事件时间（epoch 毫秒）。

响应（HTTP 200，业务结果以 `status` 区分，三种结果都不会静默丢失）：

| status        | reason               | 含义 |
|---------------|----------------------|------|
| `ACCEPTED`    | `ACCEPTED`           | 报文已计入对应窗口 |
| `DUPLICATE`   | `DUPLICATE_SEQ`      | 同品种同序号报文已存在，本次不生效（不重复计价、不重复累加成交量） |
| `LATE_DROPPED`| `WINDOW_CLOSED_LATE` | 目标窗口已关闭，迟到报文被明确丢弃；响应带 `windowStart/windowEnd` |

校验失败响应：

| HTTP | reason | 触发条件 |
|------|--------|----------|
| 400 | `MALFORMED_JSON` | 体为空 / 非法 JSON / 根节点不是对象 |
| 422 | `MISSING_SYMBOL` / `MISSING_SEQ` / `MISSING_PRICE` / `MISSING_VOLUME` / `MISSING_TIMESTAMP` | 对应字段缺失或为 null |
| 422 | `BLANK_SYMBOL` | `symbol` 为空白串 |
| 422 | `SEQ_OUT_OF_RANGE` | `seq` 非正整数 |
| 422 | `INVALID_PRICE` | `price` 非数字、非有限数（NaN/Infinity）或 ≤ 0 |
| 422 | `INVALID_VOLUME` | `volume` 非数字、非有限数或 < 0 |
| 422 | `INVALID_TIMESTAMP` | `timestamp` 非整数、≤ 0、早于 1970-01-02，或晚于当前时间 + 60s（未来容忍，可在代码中调整） |

错误响应体形如：

```json
{ "status": "REJECTED", "reason": "MISSING_TIMESTAMP", "message": "缺少时间戳 timestamp" }
```

### 1.2 查询聚合结果

- `GET /api/ticks/{symbol}/windows`：该品种全部窗口（按起点升序）
- `GET /api/ticks/{symbol}/windows/{windowStart}`：查询指定起点（epoch 毫秒）的窗口
- `GET /api/ticks/{symbol}/windows/at?timestamp=1727000000000`：查询某事件时间所属窗口

窗口对象：

```json
{
  "symbol": "IF2410",
  "windowStart": 1727000000000,
  "windowEnd":   1727000060000,
  "open": 3501.2, "high": 3510.0, "low": 3498.5, "close": 3505.0,
  "volume": 23.0, "tickCount": 9, "closed": false
}
```

## 2. 校验规则

1. 校验顺序固定：先查字段缺失（5 个字段各有独立原因码），再查格式与数值异常；
   任何拒绝都在 HTTP 响应（或提交结果）中带明确、可区分的 `reason`，绝不静默丢弃。
2. 重复判定以 `(symbol, seq)` 为键。重复报文返回 `DUPLICATE`，其携带的
   价格/数量无论是什么都不参与聚合，成交量不会被重复累加。

## 3. 窗口语义、窗口关闭与迟到数据

- 窗口为**左闭右开**的固定窗口 `[k*S, (k+1)*S)`，`S = pipeline.window-size-millis`（默认 60000ms）。
- **watermark** = 该品种已观察到的最大事件时间 `max(ts)`。
- 当 `watermark >= 窗口右端` 时，该窗口**已关闭**：之后到达的、属于该窗口的报文
  一律判定 `LATE_DROPPED / WINDOW_CLOSED_LATE` 并明确返回，**已关闭窗口永不被改写**。
- 窗口尚未关闭时，更早时间戳的报文属于**乱序（可接受的迟到）**，照常进入正确窗口。
- **OHLCV 与到达顺序无关**：`open` = 窗口内 `(timestamp, seq)` 最小的报文价格，
  `close` = 最大的报文价格（同毫秒用 seq 破并列），`high/low` 为价格极值，
  `volume` 为成交量之和。因此任意乱序到达顺序都产生同一确定结果。
- 被判定迟到丢弃的报文也会写入日志（记录类型 `LATE_DROP`），重启恢复时丢弃
  决策可复现；其 seq 同样占用去重键，再次到达仍返回 `DUPLICATE`，不会补计成交量。

边界说明：本服务以事件时间驱动 watermark，未引入外部时钟宽限期；
“关闭”只由同品种已观察到的更大事件时间触发。

## 4. 并发模型

- 每个品种一把锁：同一品种的 “去重判定 → 迟到判定 → 日志落盘 → 内存生效”
  在锁内原子完成；不同品种之间完全并行。
- 因此多线程并发提交同一品种时，内存聚合结果与这些报文按某一次序串行提交的
  结果**完全一致**（open/high/low/close/volume/tickCount 均相同）。
- 日志文件的写入在 `TickJournal` 内部再串行化并 fsync，保证多品种并发下文件不交错。
- 生效顺序为“先持久化、后改内存”：只有日志追加成功后报文才计入窗口，
  避免崩溃后“内存有、磁盘无”。

## 5. 本地持久化与重启恢复

- 数据目录由 `pipeline.data-dir` 指定（默认 `./data`，可用环境变量 `PIPELINE_DATA_DIR` 覆盖）。
- `data/tick-journal.log`：只追加的原始报文日志，每行一个 JSON，类型为
  `TICK`（接受）或 `LATE_DROP`（迟到丢弃），含到达时间 `ingestTime`。
- 每次追加都强制刷盘；启动时严格按日志行顺序重放，重建每品种的窗口与
  watermark——watermark 是由已记录报文重放出来的，因此历史的迟到丢弃不会被
  “翻案”，恢复后的窗口结果与重启前逐字段一致。
- 日志末行损坏（如写入中途断电）时恢复会直接报错中止，而不是静默截断数据。

## 6. 配置

`src/main/resources/application.properties`：

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `pipeline.window-size-millis` | `60000` | 固定窗口大小（毫秒），也可用 `PIPELINE_WINDOW_SIZE_MILLIS` 覆盖 |
| `pipeline.data-dir` | `./data` | 本地日志与运行日志目录，也可用 `PIPELINE_DATA_DIR` 覆盖 |
| `server.port` | `8080` | HTTP 端口 |

## 7. 运行与验证

```bash
# 启动服务（首次启动自动创建 ./data 与日志文件）
mvn spring-boot:run

# 提交行情
curl -s -XPOST localhost:8080/api/ticks -H 'Content-Type: application/json' \
  -d '{"symbol":"IF2410","seq":1,"price":3501.2,"volume":2,"timestamp":<当前毫秒时间戳>}'

# 查询窗口
curl -s 'localhost:8080/api/ticks/IF2410/windows'
```

### 7.1 单元/集成测试

```bash
mvn test
```

测试按业务场景组织，共 30 个用例，控制台日志会打印每个用例覆盖的业务
（以 `[用例]` / `[SUBMIT]` / `[REJECT]` / `[RECOVERY]` 前缀输出）：

| 测试类 | 覆盖业务 | 场景 |
|--------|----------|------|
| `ValidationTest` | 报文接入与校验 | 5 类字段缺失各自独立原因、空 symbol、异常价格/数量/时间戳/序号、非法 JSON |
| `DuplicateAndWindowTest` | 去重 + 窗口聚合 + 乱序/迟到 | 重复序号不累加；逆序报文 OHLCV 与顺序无关；窗口关闭后迟到报文 `LATE_DROPPED` 且不改写、不串量；窗口开启时乱序进入正确窗口；closed 标记翻转 |
| `ConcurrencyTest` | 并发一致性 | 8 线程并发提交同一品种（含重复序号，重复 5 轮），与串行参照逐字段对比 |
| `RecoveryTest` | 重启恢复 | 接受/重复/迟到丢弃后丢弃全部内存状态，仅靠本地日志重建，窗口结果逐字段一致；重启后重提迟到报文仍为 DUPLICATE |
| `HttpApiIntegrationTest` | HTTP 端到端 | 接受、422 缺字段、400 非法 JSON、DUPLICATE、LATE_DROPPED、查询、本地日志落盘 |

验证关注点（与需求逐条对应）：

- **重复**：`duplicateSeqAppliedOnce` —— 同序号提交 3 次只有 1 次生效，成交量不翻倍。
- **乱序**：`outOfOrderWithinWindow` / `lateButWindowOpenIsAccepted` —— 逆序提交
  open/close 仍由事件时间决定。
- **迟到**：`lateDataAfterWindowClosedIsDropped` —— 关闭窗口后迟到量 100 不进入
  任何窗口，响应明确标记丢弃原因。
- **并发**：`concurrentSubmissionMatchesSerial` —— 并发结果 == 串行基准。
- **重启恢复**：`recoverAfterRestart` —— 删内存、留磁盘，重放结果完全一致。

Surefire 报告位于 `target/surefire-reports/`，运行日志同时输出到控制台与
`data/pipeline.log`，可据此核对各业务的用例与测试情况。
