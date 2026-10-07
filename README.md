# dataset-query-service-java

一个本地可独立运行的数据集查询服务，供内部工具通过 HTTP 注册数据集 schema、
兼容地演进 schema（带版本历史）、写入记录、对存量记录回填字段并执行查询。
核心逻辑（校验、兼容性判断、事件日志存储、快照重放、查询）全部是进程内
实现，不依赖外部数据库、网络服务或真实凭据，可离线构建与测试。

## 运行与验证

```bash
mvn test            # 运行全部单元/集成测试（42 个用例）
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
| PUT  | `/datasets/{name}/schema` | 注册初始 schema，或提交一次兼容演进（立即生效、递增版本并留痕） |
| GET  | `/datasets/{name}/schema` | 查看当前生效 schema、当前版本号与演进历史 |
| GET  | `/datasets/{name}/schema/history` | 同上，语义化地获取版本与变更历史 |
| POST | `/datasets/{name}/records` | 批量写入记录，body: `{"records": [...]}` |
| POST | `/datasets/{name}/backfill` | 对存量记录一次性回填一个可选字段，body: `{"field": ..., "value": ..., "idempotencyKey": "..."}` |
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

- 过滤算子：`EQ` `NE` `GT` `GTE` `LT` `LTE`，多个条件为 AND 关系；
  另有 `IS_NULL` / `IS_NOT_NULL`（`value` 必须省略或为 null），显式判空；
- 聚合函数：`COUNT`（无需 field）`SUM` `AVG`（仅数值字段）`MIN` `MAX`，
  在过滤后的**全集**上计算（不受分页影响）；
- `limit` 默认 100，最大 1000；
- 响应：`{"rows": [...], "aggregations": {...}, "total": N, "offset": 0,
  "limit": 100, "snapshotSeq": 123, "snapshotStale": false}`，
  `total` 是过滤后全集大小。`snapshotStale` 见“回填与分页快照”一节。

**Schema 视图与版本历史**（GET/PUT schema 的响应）：

```json
{
  "schema": {"name": "events", "fields": [...]},
  "version": 3,
  "createdAt": "2026-10-08T01:00:00Z",
  "changes": [
    {"version": 2, "changeType": "FIELD_ADDED", "fieldName": "region",
     "fieldType": "STRING", "required": false, "effectiveAt": "2026-10-08T01:05:00Z"},
    {"version": 3, "changeType": "REQUIRED_TO_OPTIONAL", "fieldName": "price",
     "fieldType": "DOUBLE", "required": false, "effectiveAt": "2026-10-08T01:10:00Z"}
  ]
}
```

`changeType` 取值：`FIELD_ADDED`（新增可选字段）、`REQUIRED_TO_OPTIONAL`
（必填放宽为可选）。**回填**响应：

```json
{"field": "region", "value": "unknown", "backfilled": 128,
 "seq": 302, "schemaVersion": 3, "replayed": false, "idempotencyKey": "run-1"}
```

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
| `INCOMPATIBLE_SCHEMA` | 409 | 调用方错误 |
| `INTERNAL_ERROR` | 500 | 服务内部错误 |

回填失败的口径：回填未在当前 schema 声明的字段返回 400 `UNKNOWN_FIELD`；
回填值类型不符返回 400 `TYPE_MISMATCH`；回填值为 null（与“无值”不可区分、
不改变任何数据）返回 400 `VALIDATION_ERROR`。这些失败都**完全不改变数据**，
可直接重试。

请求体不是合法 JSON 等解析失败也归为 `BAD_REQUEST`；底层解析/转换异常
（如 Jackson 的异常类名、堆栈）不会透出到响应体，只会记录在服务端日志。

## Schema 兼容范围与版本历史

重复注册同名数据集的 schema 时做兼容性检查。**一旦兼容变更被接受，它在同一
原子操作内立即对所有路径生效**：后续写入可携带新字段并通过校验；schema
查询（含历史）返回最新字段；选列、过滤、聚合、排序都能使用新字段；此前写入
的记录不受影响，投影/读取新字段时得到 null，就像该字段没有值。

**兼容（接受）**
- 完全相同的 schema：不产生新版本、不留历史（no-op）；
- 新增 optional 字段（`FIELD_ADDED`）；
- 字段由 required 放宽为 optional（`REQUIRED_TO_OPTIONAL`）。

**不兼容（拒绝，返回 409 `INCOMPATIBLE_SCHEMA`，details 一次性列出全部、
且彼此可区分的冲突；数据与版本号完全不变）**
- 删除已有字段（`removed`）；
- 修改已有字段的类型（`changed type`）；
- 新增 required 字段（`is required; only optional fields may be added`）；
- 字段由 optional 收紧为 required（`changed from optional to required`）。

**版本与留痕规则**
- 每个数据集初始 schema 的版本号为 **1**；
- 只有真正改变 schema 的兼容提交才把版本号 **+1**；同一次提交中的多个字段
  变更共享同一个版本号与 `effectiveAt`；
- 版本号严格递增、不跳号、不回退；历史只增不改，可通过
  `GET /datasets/{name}/schema`（或 `/schema/history`）查看“哪个版本、
  什么时间、新增/放宽了哪些字段”；
- 并发演进在数据集级锁内“检查并提交”，彼此串行化：每个请求都针对已提交的
  前驱版本判定，结果是**整体生效或整体不生效**，任何观察者都不会看到
  “只有一部分新字段”的中间态 schema。

## 存量回填（backfill）

`POST /datasets/{name}/backfill`，body 为
`{"field": "tier", "value": 0, "idempotencyKey": "可选"}`：

- 作用范围是**提交时刻已存在**、且该字段缺值的全部记录；回填在单个序列号
  点原子提交（回填本身消耗一个序列号），因此任何时刻的读取要么全部是回填前
  状态、要么全部是回填后状态，不存在“一半有值一半没有”；
- 与并发写入有明确先后：在回填提交点之前完成的写入若缺值会被填上，在其后
  开始/完成的写入不被回填影响；写入与回填共用同一把数据集级锁与同一个单调
  序列号，二者完全可线性化；
- **要么整体成功、要么完全不动数据**：字段不存在、类型不符、值为 null 等
  失败都发生在提交之前，数据不变，可重试；
- **重试不重复回填**：携带相同 `idempotencyKey` 的重试直接重放首次结果
  （响应中 `replayed: true`，返回原 `seq` 与原填充条数，不消耗新序列号、
  不改任何数据），因此绝不会覆盖首次回填之后新写入的值；不带 key 的重复
  调用只填充当时仍缺值的记录，同样用 `putIfAbsent` 语义、绝不覆盖已有值；
- 回填完成后，按该字段做 `EQ`/范围过滤、排序和 `SUM`/`AVG`/`MIN`/`MAX`
  的结果，与“所有历史记录本来就带这个默认值”完全一致。

### 回填与分页快照

旧令牌**不会失效报错**，也不会把回填前后的数据混进同一页——存储由“追加
事件 + 回填事件”的日志重放得到快照，旧 `snapshotSeq` 永远解析到它对应
时刻的一致旧视图（旧记录在该字段上仍是 null）。同时规则在结果中显式可见：

- 若令牌之后有回填提交，后续页响应的 **`snapshotStale: true`** 会明确提示
  “存在更新的数据状态”；本页仍是一致的旧视图，`total`、页内数据保持稳定；
- 需要最新状态时，调用方应**丢弃令牌、从第 1 页重新分页**；
- 新令牌（以及不回填的普通追加之后的旧令牌）`snapshotStale` 为 `false`。

## 空值与缺失值口径

显式 `null` 与字段缺省在服务内被视为**完全相同**（写入时显式 null 会被规整
为缺省，读取时二者都得到 null）。

- **过滤**
  - `IS_NULL` 命中“无值”，`IS_NOT_NULL` 命中“有值”，二者可显式区分空值与
    普通值；`IS_NULL`/`IS_NOT_NULL` 不允许携带 `value`；
  - `EQ null` 等价于 `IS NULL`，`NE null` 等价于 `IS NOT NULL`；
  - 范围比较（`GT/GTE/LT/LTE`）遇到记录侧的 null **按“不匹配”处理**，查询
    不报错，其余命中记录照常返回；范围算子必须给非 null 比较值，否则在
    请求校验阶段以 400 `VALIDATION_ERROR` 拒绝（所有问题一次性列出）。
- **排序**：null 在升序、降序下都排最后；非 null 值按类型比较，序列号仍是
  最终决胜键，结果全序且可复现。
- **聚合**（统一口径，且作用在过滤后全集上）
  - `COUNT`（含 `COUNT` 带字段名）统计**所有**命中行，不排除 null；
  - `SUM`/`AVG`/`MIN`/`MAX` **跳过 null**；当没有任何非 null 值时结果为
    `null`（不是 0），有非 null 值时按数值/可比较类型正常计算。
- 以上语义在过滤 + 排序 + 聚合 + 分页任意组合时保持一致；固定快照令牌在
  并发写入下结果不变（已被单测反复读取验证），不带令牌的查询取读时刻快照。

## 并发行为

- **统一事件序列号**：记录追加与字段回填共用每个数据集一个单调递增的序列
  号。批量写在数据集级锁下原子分配序列号，同一批记录要么全部可见要么全部
  不可见，且按批内顺序连续可见；回填在单独一个序列号点原子生效。
- **一致性快照**：查询先确定当前序列号高水位，再重放事件日志得到
  `seq <= 高水位` 的记录视图。快照之后的并发写入/回填不会进入本次查询。
- **分页令牌（snapshotSeq）**：第一页响应携带快照高水位；后续页把它作为
  `snapshotSeq` 传回，即可让多页读取**同一份快照**。事件日志只增不改，
  同一令牌永远解析到相同记录集——并发写入期间翻页不重复、不丢数据，
  `total` 在页间保持稳定；若该令牌之后发生过回填，`snapshotStale` 置
  `true` 以显式提示。不传令牌时每次查询取新快照（读已提交语义：
  能看到查询开始时已完成的全部写入与回填）。
- **顺序稳定性**：结果按请求的 sort 字段排序后，始终以记录序列号作为最终
  决胜键，构成全序；不传 sort 时即插入顺序。同一快照上重复查询结果完全一致。
- **可见性**：单次写请求/回填返回后，其变更对后续任何查询可见（读己之写）；
  服务不提供跨请求的事务。

## 测试覆盖

| 测试类 | 覆盖场景 |
|--------|----------|
| `SchemaValidationTest` | 字段缺失 / 类型不匹配 / 未知字段三类校验失败及错误码可区分性 |
| `SchemaCompatibilityTest` | 兼容演进被接受、四类不兼容变更被拒绝 |
| `SchemaEvolutionTest` | 兼容演进立即对写入/schema 查询/选列/过滤/聚合生效；版本递增与历史留痕；不兼容变更一次性列出全部冲突且不留半成品；并发演进整体生效或整体不生效 |
| `BackfillTest` | 存量回填原子生效且过滤/聚合等价于默认值世界；与并发写入的先后一致、不存在半填充视图；失败后重试不重复回填、不覆盖新值（幂等键）；旧快照保持旧视图并显式 `snapshotStale` |
| `NullSemanticsTest` | 显式 null 与缺省等价；范围比较遇 null 不拖垮查询只不匹配；IS NULL/IS NOT NULL 显式判空；COUNT 与 SUM/AVG/MIN/MAX 的空值口径；过滤+排序+聚合+分页组合一致；并发写入下固定快照结果稳定 |
| `ConcurrentPaginationTest` | 并发写入下持令牌分页不重复不丢数据、total 稳定、默认序与排序决胜键确定 |
| `HttpApiTest` | 端到端读写查询、统一错误结构、4xx/5xx 分类、解析异常不透出、HTTP 层分页令牌 |
| `EvolutionBackfillHttpTest` | HTTP 端到端：版本化 schema 与历史、回填与幂等重放、旧令牌 stale 标记、IS NULL/IS NOT NULL |

## 项目结构

```
src/main/java/com/github/feng111627/datasetqueryservicejava/
├── model/      数据契约：schema/版本视图/演进记录、回填请求与结果、查询请求/结果、快照
├── error/      错误码与 ClientException(4xx)/ServerException(5xx) 分层
├── schema/     记录校验（SchemaValidator）、兼容性检查与演进 diff（SchemaCompatibility）
├── store/      追加/回填统一事件日志、锁内原子提交、按序列号重放的一致性快照（DatasetStore）
├── query/      空值安全的过滤/选列/聚合/稳定排序/分页（QueryEngine）
├── service/    编排层：演进提交、写入、回填、查询（DatasetService）
└── web/        REST 控制器与统一异常映射（GlobalExceptionHandler）
```
