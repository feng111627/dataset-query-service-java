# dataset-query-service-java

一个本地可独立运行的数据集查询服务，供内部工具通过 HTTP 注册数据集 schema、
写入记录并执行查询。核心逻辑（校验、兼容性判断、存储、查询）全部是进程内
实现，不依赖外部数据库、网络服务或真实凭据，可离线构建与测试。

## 运行与验证

```bash
mvn test            # 运行全部单元/集成测试（45 个用例）
mvn spring-boot:run # 本地启动服务（默认随机端口可用 -Dserver.port=8080 指定）
```

测试日志中带 `[COVERAGE]` 前缀的行标明每个测试类覆盖的业务场景、每个用例的
通过情况以及类级汇总，例如：

```
[COVERAGE] === ConcurrentPaginationTest | business scenario: concurrent writes during pagination: ... ===
[COVERAGE] << PASS: paginating with a snapshot token while a writer appends: ...
[COVERAGE] === ConcurrentPaginationTest finished: 4 passed, 0 failed ===
```

## HTTP API

| 方法 | 路径 | 说明 |
|------|------|------|
| PUT  | `/datasets/{name}/schema` | 注册 schema，或提交一次兼容演进 |
| GET  | `/datasets/{name}/schema` | 查看当前生效的 schema（含演进后的最新字段） |
| GET  | `/datasets/{name}/schema/history` | 查看 schema 版本历史（哪个版本新增/放宽了哪些字段、何时生效） |
| POST | `/datasets/{name}/records` | 批量写入记录，body: `{"records": [...]}` |
| POST | `/datasets/{name}/backfill` | 给存量记录回填字段默认值，body: `{"field": "...", "value": ..., "requestId": "..."}` |
| POST | `/datasets/{name}/query` | 查询，body 见下文 |

### 数据契约

**Schema 声明**（PUT schema 的 body）：

```json
{
  "fields": [
    {"name": "id",    "type": "INTEGER", "required": true},
    {"name": "price", "type": "DOUBLE",  "required": true},
    {"name": "label", "type": "STRING",  "required": false}
  ]
}
```

字段类型：`STRING` / `INTEGER` / `DOUBLE` / `BOOLEAN`。`INTEGER` 只接受整数值
（`1.5` 会被拒绝），`DOUBLE` 接受任意 JSON 数值。`required: false` 的字段可以
缺省；显式 `null` 视为缺省。

**写入校验**：每条记录按 schema 严格校验，任一问题都会拒绝整批写入
（all-or-nothing），错误原因彼此可区分：

| 错误码 | 含义 |
|--------|------|
| `MISSING_FIELD` | 缺少必填字段 |
| `TYPE_MISMATCH` | 字段值类型与声明不符 |
| `UNKNOWN_FIELD` | 出现 schema 未声明的字段 |

**查询请求**（POST query 的 body，全部字段可选）：

```json
{
  "filters":     [{"field": "price", "op": "GTE", "value": 20}],
  "columns":     ["id", "price"],
  "aggregations":[{"func": "SUM", "field": "price", "alias": "total_price"}],
  "sort":        [{"field": "price", "ascending": false}],
  "offset": 0,
  "limit": 100,
  "snapshotSeq": 123
}
```

- 过滤算子：`EQ` `NE` `GT` `GTE` `LT` `LTE` `IS_NULL` `IS_NOT_NULL`，
  多个条件为 AND 关系（空值语义见下文「空值处理口径」）；
- 聚合函数：`COUNT`（无需 field）`SUM` `AVG`（仅数值字段）`MIN` `MAX`，
  在过滤后的**全集**上计算（不受分页影响）；
- `limit` 默认 100，最大 1000；
- 响应：`{"rows": [...], "aggregations": {...}, "total": N, "offset": 0,
  "limit": 100, "snapshotSeq": 123}`，`total` 是过滤后全集大小。

### 错误结构

所有错误响应都是统一结构，HTTP 状态码区分“调用方用错”（4xx）与
“服务内部出错”（5xx）：

```json
{"error": {"code": "TYPE_MISMATCH", "message": "...", "details": ["price (expected DOUBLE, got String)"]}}
```

| 错误码 | HTTP | 类别 |
|--------|------|------|
| `MISSING_FIELD` / `TYPE_MISMATCH` / `UNKNOWN_FIELD` / `VALIDATION_ERROR` / `BAD_REQUEST` | 400 | 调用方错误 |
| `SCHEMA_NOT_FOUND` | 404 | 调用方错误 |
| `INCOMPATIBLE_SCHEMA` / `SNAPSHOT_EXPIRED` | 409 | 调用方错误 |
| `INTERNAL_ERROR` | 500 | 服务内部错误 |

请求体不是合法 JSON 等解析失败也归为 `BAD_REQUEST`；底层解析/转换异常
（如 Jackson 的异常类名、堆栈）不会透出到响应体，只会记录在服务端日志。

## Schema 演进与版本历史

再次 PUT 同名数据集的 schema 即提交一次演进。兼容性规则：

**兼容（接受并立即生效）**
- 完全相同的 schema（幂等，不产生新版本）；
- 新增 optional 字段；
- 字段由 required 放宽为 optional。

**不兼容（拒绝，返回 409 `INCOMPATIBLE_SCHEMA`，details 一次性列出全部冲突）**
- 删除已有字段；
- 修改已有字段的类型；
- 新增 required 字段；
- 字段由 optional 收紧为 required。

被接受的演进**立即生效**：后续写入可以携带新字段并通过校验，GET schema
返回最新字段，新字段可以立即用于选列、过滤和聚合；演进前写入的记录不受
影响，读到新字段时按“无值”（null）处理。

**版本历史**：初始注册为版本 1，此后每次被接受的演进版本号递增 1。
`GET /datasets/{name}/schema/history` 返回全部版本，每项包含
`version`、`effectiveAt`（生效时间）、`addedFields`（该版本新增的可选字段）、
`relaxedFields`（该版本由必填放宽为可选的字段）。

**并发演进**：兼容性检查与新 schema 的生效在同一个数据集级锁内完成，
因此并发提交的演进被逐个串行化——每次演进要么整体生效（新版本对所有
后续读者立即可见），要么整体失败且不留下任何中间状态。两个并发演进若
各自缺少对方新增的字段，后到者会因“字段被删除”被判不兼容而整体拒绝；
调用方基于最新 schema 重试即可成功。多个并发请求提交**相同**的演进时，
只记录一个新版本，其余按幂等处理。

## 存量回填（backfill）

`POST /datasets/{name}/backfill`，body：

```json
{"field": "label", "value": "unknown", "requestId": "bf-20261008-1"}
```

- **作用范围**：对回填开始时已存在的全部记录，把缺失或为 null 的
  `field` 填成 `value`；已有非 null 值的记录保持不变。`value` 不能为
  null，且类型必须与字段声明一致（否则 400 `TYPE_MISMATCH`）。
- **与并发写的关系**：回填与写入在同一把数据集级锁下串行。一条记录
  要么在回填前写入（被回填），要么在回填后写入（绝不被回填触碰）；
  任何时刻的读要么看到完整的回填前状态，要么看到完整的回填后状态，
  不会出现“一半记录有值一半没有”。
- **原子与重试**：回填先构造完整的新记录集再一次性换入，要么整体成功
  要么完全不改变数据，失败后可直接重试。`requestId` 是幂等键：以相同
  `requestId` + 相同参数重试会返回首次的结果（`replayed: true`）而不
  再触碰任何数据——不会重复回填，也不会修改回填之后新写入的记录；
  同一 `requestId` 换用不同参数会被拒绝（400）。
- **回填后的查询**：回填完成后，按该字段过滤/聚合的结果与“所有历史
  记录一开始就带着这个默认值”完全一致。
- **对分页快照的影响**：改变了数据的回填会使**之前已取到的快照令牌
  失效**——再拿旧 `snapshotSeq` 翻页会收到 409 `SNAPSHOT_EXPIRED`，
  调用方需放弃旧令牌、重新取新快照翻页。服务绝不会把回填前后的数据
  悄悄混进同一页。没有实际改动任何记录的回填（全部记录本就有值）不
  会使快照失效。

响应示例：`{"field": "label", "value": "unknown", "backfilled": 5,
"highWaterMark": 10, "replayed": false}`。

## 空值处理口径

可选字段缺失或显式为 null 都是正常的“空值”，不会导致查询失败：

- **范围比较**（`GT` `GTE` `LT` `LTE`）：字段为空值的记录按**不匹配**
  处理，其余命中的记录正常返回；比较值本身为 null 属于调用方错误
  （400 `VALIDATION_ERROR`）。
- **显式判空**：`IS_NULL` / `IS_NOT_NULL` 直接按“字段是否为空”过滤
  （不需要 `value`）。`EQ null` 等价于 `IS_NULL`，`NE null` 等价于
  `IS_NOT_NULL`。
- **聚合**：`COUNT` 统计命中的记录数（含空值记录）；`SUM`/`AVG` 跳过
  空值，只对非空数值计算——全为空（或空集）时 `SUM` 为 0、`AVG` 为
  null；`MIN`/`MAX` 跳过空值，无非空值时为 null。
- **排序**：空值永远排在最后（升序、降序一致），记录序列号仍是最终
  决胜键。
- 以上规则在过滤、排序、聚合、分页任意组合以及并发写入下保持一致
  （同一快照内结果可复现）。

## 并发行为

- **追加式存储**：每个数据集有一个单调递增的序列号，批量写在数据集级锁下
  原子分配序列号，因此同一批记录要么全部可见要么全部不可见，且按批内顺序
  连续可见。
- **一致性快照**：查询先读取当前序列号高水位，再收集 `seq <= 高水位` 的记录。
  快照之后的并发写入不会进入本次查询结果。
- **分页令牌（snapshotSeq）**：第一页响应携带快照令牌；后续页把它作为
  `snapshotSeq` 传回，即可让多页读取**同一份快照**。令牌是不透明数值，
  内部编码了“数据时代（epoch）+ 序列号高水位”：两次回填之间存储只增
  不改，同一令牌永远解析到相同记录集——并发写入期间翻页不重复、不丢
  数据，`total` 在页间保持稳定。改变了数据的回填会推进数据时代，使旧
  令牌失效（409 `SNAPSHOT_EXPIRED`，见「存量回填」）。不传令牌时每次
  查询取新快照（读已提交语义：能看到查询开始时已完成的全部写入）。
- **顺序稳定性**：结果按请求的 sort 字段排序后，始终以记录序列号作为最终
  决胜键，构成全序；不传 sort 时即插入顺序。同一快照上重复查询结果完全一致。
- **可见性**：单次写请求返回后，其记录对后续任何查询可见（读己之写）；
  服务不提供跨请求的事务。

## 测试覆盖

| 测试类 | 覆盖场景 |
|--------|----------|
| `SchemaValidationTest` | 字段缺失 / 类型不匹配 / 未知字段三类校验失败及错误码可区分性 |
| `SchemaCompatibilityTest` | 兼容演进被接受、四类不兼容变更被拒绝 |
| `SchemaEvolutionTest` | 兼容演进立即生效（写入/查询/选列/过滤/聚合）、版本递增与历史留痕、不兼容变更整体拒绝、并发演进串行化与幂等 |
| `BackfillTest` | 存量回填覆盖全部既有记录、与并发写入的先后一致性、失败重试幂等、不触碰回填后的写入、快照令牌失效规则 |
| `NullSemanticsTest` | 空值参与范围比较不拖垮查询、IS_NULL/IS_NOT_NULL 显式判空、聚合空值口径、空值排序恒在最后、组合分页与并发写入下稳定 |
| `ConcurrentPaginationTest` | 并发写入下持令牌分页不重复不丢数据、total 稳定、默认序与排序决胜键确定 |
| `HttpApiTest` | 端到端读写查询、统一错误结构、4xx/5xx 分类、解析异常不透出、HTTP 层分页令牌、HTTP 层 schema 演进/历史与回填 |

## 项目结构

```
src/main/java/com/github/feng111627/datasetqueryservicejava/
├── model/      数据契约：schema、版本历史、记录、查询/回填请求与结果、快照
├── error/      错误码与 ClientException(4xx)/ServerException(5xx) 分层
├── schema/     记录校验（SchemaValidator）与兼容性检查（SchemaCompatibility）
├── store/      追加式内存存储、一致性快照、原子回填与 schema 版本历史（DatasetStore）
├── query/      过滤/选列/聚合/稳定排序/分页（QueryEngine）
├── service/    编排层（DatasetService）
└── web/        REST 控制器与统一异常映射（GlobalExceptionHandler）
```
