# dataset-query-service-java

一个本地可独立运行的数据集查询服务，供内部工具通过 HTTP 注册数据集 schema、
写入记录并执行查询。核心逻辑（校验、兼容性判断、存储、查询）全部是进程内
实现，不依赖外部数据库、网络服务或真实凭据，可离线构建与测试。

## 运行与验证

```bash
mvn test            # 运行全部单元/集成测试（25 个用例）
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
| PUT  | `/datasets/{name}/schema` | 注册（或兼容地重复注册）schema |
| GET  | `/datasets/{name}/schema` | 查看当前生效的 schema |
| POST | `/datasets/{name}/records` | 批量写入记录，body: `{"records": [...]}` |
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
| `INCOMPATIBLE_SCHEMA` | 409 | 调用方错误 |
| `INTERNAL_ERROR` | 500 | 服务内部错误 |

请求体不是合法 JSON 等解析失败也归为 `BAD_REQUEST`；底层解析/转换异常
（如 Jackson 的异常类名、堆栈）不会透出到响应体，只会记录在服务端日志。

## Schema 兼容规则

重复注册同名数据集的 schema 时做兼容性检查：

**兼容（接受，沿用已存储的数据与原 schema）**
- 完全相同的 schema；
- 新增 optional 字段；
- 字段由 required 放宽为 optional。

**不兼容（拒绝，返回 409 `INCOMPATIBLE_SCHEMA`，details 列出全部冲突）**
- 删除已有字段；
- 修改已有字段的类型；
- 新增 required 字段；
- 字段由 optional 收紧为 required。

## 并发行为

- **追加式存储**：每个数据集有一个单调递增的序列号，批量写在数据集级锁下
  原子分配序列号，因此同一批记录要么全部可见要么全部不可见，且按批内顺序
  连续可见。
- **一致性快照**：查询先读取当前序列号高水位，再收集 `seq <= 高水位` 的记录。
  快照之后的并发写入不会进入本次查询结果。
- **分页令牌（snapshotSeq）**：第一页响应携带快照高水位；后续页把它作为
  `snapshotSeq` 传回，即可让多页读取**同一份快照**。由于存储只增不改，
  同一令牌永远解析到相同记录集——并发写入期间翻页不重复、不丢数据，
  `total` 在页间保持稳定。不传令牌时每次查询取新快照（读已提交语义：
  能看到查询开始时已完成的全部写入）。
- **顺序稳定性**：结果按请求的 sort 字段排序后，始终以记录序列号作为最终
  决胜键，构成全序；不传 sort 时即插入顺序。同一快照上重复查询结果完全一致。
- **可见性**：单次写请求返回后，其记录对后续任何查询可见（读己之写）；
  服务不提供跨请求的事务。

## 测试覆盖

| 测试类 | 覆盖场景 |
|--------|----------|
| `SchemaValidationTest` | 字段缺失 / 类型不匹配 / 未知字段三类校验失败及错误码可区分性 |
| `SchemaCompatibilityTest` | 兼容演进被接受、四类不兼容变更被拒绝 |
| `ConcurrentPaginationTest` | 并发写入下持令牌分页不重复不丢数据、total 稳定、默认序与排序决胜键确定 |
| `HttpApiTest` | 端到端读写查询、统一错误结构、4xx/5xx 分类、解析异常不透出、HTTP 层分页令牌 |

## 项目结构

```
src/main/java/com/github/feng111627/datasetqueryservicejava/
├── model/      数据契约：schema、记录、查询请求/结果、快照
├── error/      错误码与 ClientException(4xx)/ServerException(5xx) 分层
├── schema/     记录校验（SchemaValidator）与兼容性检查（SchemaCompatibility）
├── store/      追加式内存存储与一致性快照（DatasetStore）
├── query/      过滤/选列/聚合/稳定排序/分页（QueryEngine）
├── service/    编排层（DatasetService）
└── web/        REST 控制器与统一异常映射（GlobalExceptionHandler）
```
