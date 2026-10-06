# 本地行情接入与窗口聚合服务

基于 Spring Boot 3 / Java 21 的本地行情（tick）接入服务：接收行情报文、做字段校验与
按品种序号去重，按固定时间窗口聚合出 OHLC（开/高/低/收）与成交量，处理乱序与迟到数据。
**状态与原始数据全部落在本地文件，不依赖任何外部中间件（无数据库 / Kafka / Redis）**；
进程重启后从持久化的原始报文重放恢复，聚合结果与重启前完全一致。

## 1. HTTP 接口

所有接口前缀 `/api`，报文以原始 JSON 作为请求体。

### 1.1 提交行情

`POST /api/ticks`

请求体：

```json
{ "symbol": "IF2401", "seq": 1001, "price": 3850.2, "quantity": 3, "timestamp": 1728200000000 }
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `symbol` | string，非空白 | 品种代码 |
| `seq` | 整数，>= 1 | 品种内报文序号，用于去重 |
| `price` | number，有限且 > 0 | 成交价 |
| `quantity` | number，有限且 > 0 | 成交数量 |
| `timestamp` | 整数，>= 0 | 业务（交易所）时间戳，epoch 毫秒 |

响应：

- 生效：`201 Created`
  ```json
  { "accepted": true, "duplicate": false, "late": false,
    "message": "accepted", "symbol": "IF2401", "seq": 1001, "windowStart": 1728199980000 }
  ```
- 重复序号：`409 Conflict`，`reason = DUPLICATE_SEQ`
- 迟到且窗口已关闭：`422 Unprocessable Entity`，`reason = LATE_CLOSED_WINDOW`
- 其余校验失败：`400 Bad Request`，`reason` 为下表中的可区分原因

错误响应体形如 `{ "result": {...}, "error": { "reason": "...", "message": "...", "field": "...", ... } }`。

### 1.2 查询聚合

- `GET /api/symbols/{symbol}/windows`：返回该品种全部窗口，按窗口起始时间升序。
- `GET /api/symbols/{symbol}/windows/at?timestamp={epochMillis}`：返回某业务时间戳所属窗口，不存在返回 `404`。

窗口对象：

```json
{
  "symbol": "SMK", "windowStart": 0, "windowEnd": 10000,
  "open": 10.0, "high": 20.0, "low": 10.0, "close": 20.0,
  "volume": 3.0, "tickCount": 2, "closed": true
}
```

## 2. 校验规则与拒绝原因（可区分，绝不静默丢弃）

校验顺序固定为：报文语法 → 根类型 → 必填字段 → 字段类型 → 取值。每个原因都有独立 `reason`，
既在 HTTP 响应中返回，也追加写入本地 `rejected.jsonl` 审计日志（含原始报文、字段、时间）。

| reason | 触发条件 | HTTP 状态码 |
| --- | --- | --- |
| `MALFORMED_JSON` | 请求体为空或不是合法 JSON | 400 |
| `NOT_AN_OBJECT` | JSON 根节点不是对象（如数组、裸字符串） | 400 |
| `MISSING_FIELD` | 缺少 `symbol`/`seq`/`price`/`quantity`/`timestamp` 任一字段或为 null（`error.field` 指出字段名） | 400 |
| `INVALID_TYPE` | 字段类型不合法（如 `price` 传字符串、`seq` 传小数、`timestamp` 传布尔） | 400 |
| `BLANK_SYMBOL` | `symbol` 为纯空白 | 400 |
| `INVALID_SEQ` | `seq < 1` | 400 |
| `INVALID_PRICE` | `price` 非有限数（NaN/Infinity）或 <= 0 | 400 |
| `INVALID_QUANTITY` | `quantity` 非有限数或 <= 0 | 400 |
| `INVALID_TIMESTAMP` | `timestamp < 0` | 400 |
| `DUPLICATE_SEQ` | 同一品种该 `seq` 已生效过（即使内容被篡改也不二次计价） | 409 |
| `LATE_CLOSED_WINDOW` | 报文所属窗口已按水位线永久关闭 | 422 |

## 3. 窗口语义、关闭与迟到处理

- **固定窗口**：窗口大小 `pipeline.window-size-ms`（默认 60000ms）。窗口起始
  `w = floor(ts / size) * size`，窗口区间 `[w, w+size)`，右端不含。
- **水位线（watermark）**：`watermark = 该品种已观察到的最大业务时间戳 - allowedLatenessMs`，
  其中 `pipeline.allowed-lateness-ms` 默认 5000ms。
- **窗口关闭**：当 `watermark >= w + size` 时，窗口 `w` 永久关闭，查询结果中 `closed=true`。
- **迟到处理**：
  - 报文所属窗口**尚未关闭**（处于允许迟到范围内）：进入**正确的**窗口参与聚合，即使它晚到也不丢量。
  - 报文所属窗口**已经关闭**：被明确拒绝（`LATE_CLOSED_WINDOW`），**不聚合、不写入原始日志**，
    已关闭窗口的 OHLC/成交量不会被任何迟到数据改写。
  - 窗口是否关闭只由该报文**到达之前**的水位线决定；报文自身推进的水位线不会反过来关闭它自己的窗口。
- **乱序 OHLC**：`open`/`close` 按**业务时间戳**而不是到达顺序确定：
  - open = 窗口内时间戳最小者的价格（时间戳相同取 seq 小者）；
  - close = 窗口内时间戳最大者的价格（时间戳相同取 seq 大者）；
  - high/low 为极值；volume 为数量之和。因此任意到达顺序下聚合结果一致，且不会重复计价或丢量。

## 4. 并发模型

- **按品种分片加锁**：每个品种持有一把 monitor 锁（`SymbolAggregateState` 实例）。
  同一品种的"去重判定 → 窗口关闭判定 → 原始日志落盘 → 内存聚合"在同一把锁内原子完成，
  因此多线程并发提交同一品种的结果，与把同一批报文按任意顺序串行提交**逐字段完全一致**；
  不同品种使用不同锁，互不阻塞。
- **同序号竞争**：并发提交相同 `(symbol, seq)` 时，恰好一个线程生效，其余全部得到
  `DUPLICATE_SEQ`，成交量只计一次。
- **持久化原子性**：先把原始报文 append 到本地日志并 `flush + fsync` 强制刷盘成功，
  才修改内存聚合；落盘失败则内存保持不变。因此"已生效"与"已持久化"严格对应。

## 5. 本地持久化与重启恢复

数据目录由 `pipeline.data-dir` 指定（默认 `./data`），结构：

```
data/
├── ticks/
│   └── <symbol>.jsonl      # 每品种一个 append-only 原始报文日志（恢复的唯一事实来源）
└── rejected.jsonl           # 所有被拒绝报文 + 原因（审计，保证异常报文不静默丢弃）
```

- 只有**生效**报文才写入 `ticks/<symbol>.jsonl`（每行一个 JSON，含 symbol/seq/price/quantity/ts）；
  重复报文和窗口关闭后的迟到报文不会进入该日志，因此恢复后不会重复累加。
- 启动时 `RecoveryRunner` 扫描全部品种日志，按文件内顺序（即当时的生效顺序）调用与在线提交
  完全相同的聚合逻辑重放，重建窗口、水位线与已关闭状态。恢复结果与重启前逐窗口一致。
- 文件名对品种名做了百分号转义，避免特殊字符/路径分隔符造成目录穿越。

## 6. 配置项（`src/main/resources/application.properties`）

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `server.port` | `0`（随机端口） | HTTP 端口，部署时可固定 |
| `pipeline.data-dir` | `./data` | 本地数据目录 |
| `pipeline.window-size-ms` | `60000` | 固定窗口大小（毫秒） |
| `pipeline.allowed-lateness-ms` | `5000` | 允许迟到量（毫秒） |

## 7. 构建、运行与验证

```bash
# 运行全部单测（业务覆盖日志在控制台 BUSINESS-COVERAGE logger 下输出）
mvn test

# 打包并以固定端口 / 自定义窗口参数运行
mvn package -DskipTests
java -jar target/market-tick-pipeline-java-1.0-SNAPSHOT.jar \
  --server.port=8080 \
  --pipeline.data-dir=./data \
  --pipeline.window-size-ms=10000 \
  --pipeline.allowed-lateness-ms=2000

# 提交 / 查询
curl -X POST localhost:8080/api/ticks -H 'Content-Type: application/json' \
  -d '{"symbol":"SMK","seq":1,"price":10,"quantity":1,"timestamp":1000}'
curl localhost:8080/api/symbols/SMK/windows
```

重启进程后观察日志中的 `恢复完成 symbol=... 重放报文数=...`，查询结果与重启前一致即验证了恢复。

## 8. 测试组织（按业务场景）

测试位于 `src/test/java/.../tests`，`BusinessLogExtension` 会在日志中打印"业务分组 → 用例 → 断言要点"，
运行 `mvn test` 可直接看到覆盖了哪些业务及每个用例的通过情况。

| 测试类 | 覆盖业务 | 关键用例 |
| --- | --- | --- |
| `ValidationBusinessTest` | 接入与校验 | 合法报文、缺字段、类型错误、价格/数量/时间戳/序号/品种异常、非法 JSON、拒绝全部入审计日志 |
| `DeduplicateBusinessTest` | 序号去重 | 重复（含篡改内容）只生效一次不累加量、序号按品种隔离、重复报文不落盘 |
| `WindowAggregationBusinessTest` | 窗口聚合/乱序/迟到 | 窗口对齐、乱序 OHLC、同时间戳 tie-break、允许迟到并入、关闭后迟到丢弃、综合不重不漏 |
| `ConcurrencyBusinessTest` | 并发提交 | 8 线程 800 笔乱序并发结果等于串行参考结果；8 线程抢同序号恰好一个成功 |
| `RecoveryBusinessTest` | 重启恢复 | 重启后逐窗口一致、关闭状态随重放重建、重启后迟到仍拒绝、继续写入后二次重启仍一致 |
| `HttpApiTest` | HTTP 端到端 | 201/409/422/400 状态码与可区分 reason、窗口查询 |

## 9. 代码结构

```
model/       Tick、WindowAggregate、IngestResult、RejectReason、RejectedTick
exception/   TickRejectedException（携带原因与定位字段）
validation/  TickValidator（JSON 解析 + 分级校验）
store/       TickLogStore（本地 append-only 日志 + fsync + 恢复扫描）
service/     SymbolAggregateState（序号集/窗口/水位线）、WindowState（OHLC 状态机）、
             AggregationService（品种锁、提交/去重/迟到/查询）、RecoveryRunner（启动重放）
web/         TickController、ApiError
```
