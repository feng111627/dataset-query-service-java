# Dataset Query Service（本地数据集查询服务）

一个零外部依赖、本地即可独立运行的数据集查询服务：通过 HTTP 暴露数据集的
**注册 / 写入 / 查询** 能力。数据全部存放在进程内存中，不需要数据库、网络服务或真实凭据，
适合内部工具临时调数、功能演示与离线验证。

- Java 21+ / Spring Boot 3.4 / Maven
- 纯内存存储，重启即清空
- 内置 51 个单元/集成测试，可一键 `mvn test`

---

## 1. 快速开始

```bash
# 构建并运行测试
mvn test

# 打包并启动（默认随机端口；这里固定为 18080）
mvn package -DskipTests
java -jar target/dataset-query-service-java-1.0-SNAPSHOT.jar --server.port=18080
```

最小使用流程：

```bash
# 1) 注册数据集
curl -X POST localhost:18080/datasets -H 'Content-Type: application/json' -d '{
  "name": "orders",
  "schema": {"fields": [
    {"name": "id",       "type": "INTEGER", "required": true},
    {"name": "customer", "type": "STRING",  "required": true},
    {"name": "amount",   "type": "NUMBER",  "required": false},
    {"name": "paid",     "type": "BOOLEAN", "required": true}
  ]}
}'

# 2) 写入记录（整批原子校验、原子写入）
curl -X POST localhost:18080/datasets/orders/records \
  -H 'Content-Type: application/json' -d '{
    "records": [
      {"id": 1, "customer": "alice", "amount": 100.5, "paid": true},
      {"id": 2, "customer": "bob",   "amount": 50,    "paid": false}
    ]
  }'

# 3) 查询（过滤 + 选列 + 分页）
curl -X POST localhost:18080/datasets/orders/query \
  -H 'Content-Type: application/json' -d '{
    "filters": [{"field": "customer", "op": "EQ", "value": "alice"}],
    "select": ["id", "amount"],
    "limit": 20, "offset": 0
  }'
```

---

## 2. HTTP 接口总览

| 方法 | 路径 | 说明 | 成功状态码 |
| --- | --- | --- | --- |
| POST | `/datasets` | 注册新数据集；重名返回 409 | 201 |
| PUT | `/datasets/{name}` | 注册（不存在时）或兼容更新 schema；可随 `records` 写入首批数据 | 201 / 200 |
| GET | `/datasets/{name}` | 查看 schema 与当前记录数 | 200 |
| POST | `/datasets/{name}/records` | 写入一批记录（整批校验，失败全批拒绝） | 201 |
| POST | `/datasets/{name}/query` | 条件过滤 / 选列 / 聚合 / 分页查询 | 200 |

### 2.1 类型系统

| schema 类型 | JSON 形态 | Java 映射 |
| --- | --- | --- |
| `STRING` | 字符串 | `String` |
| `INTEGER` | 整数字面量 | `Integer/Long/BigInteger`（`3.0` 是浮点，**不**算 INTEGER） |
| `NUMBER` | 整数或小数 | 任意 `Number` |
| `BOOLEAN` | `true/false` | `Boolean` |

字段声明中 `required` 缺省为 `true`；可选字段可以缺失或显式为 `null`。

### 2.2 写入响应

```json
{ "accepted": 2, "fromId": 1, "toId": 2, "watermark": 2 }
```

每条记录由服务端分配从 1 开始、全局连续的单调递增 `id`（写入顺序即 id 顺序）。

### 2.3 查询请求

```json
{
  "filters": [
    {"field": "customer", "op": "EQ", "value": "alice"},
    {"field": "amount",   "op": "GTE", "value": 100}
  ],
  "select": ["id", "amount"],
  "groupBy": ["customer"],
  "aggregations": [
    {"op": "COUNT", "field": null,    "alias": "cnt"},
    {"op": "SUM",   "field": "amount", "alias": "total"}
  ],
  "limit": 20,
  "offset": 0,
  "watermark": null
}
```

- **过滤算子**：`EQ / NE / GT / GTE / LT / LTE`。数值按数值大小比较（整数与小数可比较）；
  字符串、布尔按同类型比较；`null` 仅支持 `EQ/NE`；跨类型不可比较（除 `NE` 外不匹配）。
- **选列**：`select` 为空/缺省返回全部字段，否则按请求顺序投影。
- **聚合**：`COUNT / SUM / AVG / MIN / MAX`。
  - `COUNT` 的 `field` 传 `null` 表示 `COUNT(*)`；带字段时不统计该字段为 `null` 的行。
  - `SUM/AVG/MIN/MAX` 只能作用于 `INTEGER/NUMBER` 字段；`null` 不参与计算，`AVG` 全为 `null` 时结果为 `null`。
  - 使用 `groupBy` 时必须至少带一个聚合；聚合结果按分组键升序排列。
- **排序（稳定性）**：非聚合查询按 schema 的第一个字段升序，并以记录 `id` 作为最终决胜键，
  因此结果是全序、确定且可复现的。
- **分页**：`limit`（默认 100，最大 1000）+ `offset`；响应带 `totalMatched / hasMore / watermark`。

### 2.4 查询响应

```json
{
  "watermark": 2,
  "totalMatched": 1,
  "limit": 20,
  "offset": 0,
  "hasMore": false,
  "aggregated": false,
  "rows": [ {"id": 1, "amount": 100.5} ]
}
```

---

## 3. 数据契约：Schema 校验

写入（以及 PUT 随附的首批记录）严格按声明的 schema 校验。三类失败原因彼此可区分，
每条问题都带 `index`（批次内记录下标）、`field`、`kind`、`message`：

| `kind` | 触发条件 |
| --- | --- |
| `missing_field` | 必填字段缺失，或值显式为 `null` |
| `type_mismatch` | 字段存在但值类型与声明不符（如 INTEGER 收到字符串或 `3.0`） |
| `unknown_field` | 出现 schema 未声明的字段 |

- 同一条记录上的多个问题会**全部收集**，不会只报第一个。
- 一批记录中任意一条不合规，**整批拒绝**（all-or-nothing），不会写入任何一条。
- 结构层面的输入问题（空 schema、字段重名、非法类型名、空 records、未知 JSON 字段、非法 JSON）
  返回 `MALFORMED_REQUEST`，与数据内容校验 `VALIDATION_FAILED` 分开。

---

## 4. 错误契约

所有错误返回统一结构：

```json
{ "code": "VALIDATION_FAILED", "message": "……", "details": [ ... ] }
```

**调用方错误（4xx）** 与 **服务内部错误（500）** 明确分开：

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | `MALFORMED_REQUEST` | JSON 非法/字段类型无法反序列化/未知 JSON 字段/schema 结构不合法 |
| 400 | `INVALID_QUERY` | 查询引用不存在的字段、比较值类型不符、limit 非法、对字符串求和等 |
| 404 | `DATASET_NOT_FOUND` | 数据集或接口不存在 |
| 409 | `DATASET_ALREADY_EXISTS` | POST 注册重名数据集 |
| 409 | `SCHEMA_INCOMPATIBLE` | PUT 提交了不兼容的 schema 变更 |
| 422 | `VALIDATION_FAILED` | 记录未通过 schema 校验，`details` 给出每条原因 |
| 500 | `INTERNAL_ERROR` | 兜底：任何未预期异常；只返回固定文案，堆栈仅写服务端日志 |

底层的 Jackson 解析/转换异常（如 `HttpMessageNotReadableException`）不会透出到响应体，
对外统一是一句概括信息；异常细节仅在服务端 `DEBUG` 日志里保留。

---

## 5. Schema 兼容规则

`PUT /datasets/{name}` 对已存在数据集做“面向存量数据”的兼容性检查：

| 变更 | 是否兼容 | 原因 |
| --- | --- | --- |
| schema 完全不变 | ✅ | — |
| 新增**可选**字段 | ✅ | 旧记录缺该列按 `null` 处理 |
| 必填字段放宽为可选 | ✅ | 旧记录依然满足更宽松的约束 |
| 字段顺序调整 | ✅ | 不影响存量数据 |
| 删除已有字段 | ❌ | 旧记录仍带该列 |
| 字段类型变更 | ❌ | 旧值无法满足新类型 |
| 可选字段收紧为必填 | ❌ | 旧记录可能缺值 |
| 新增**必填**字段 | ❌ | 旧记录没有该列 |

不兼容时返回 409 `SCHEMA_INCOMPATIBLE`，`message` 指出具体字段与原因，且**不会**改动现有 schema。

---

## 6. 并发行为与一致性说明

### 6.1 写入原子性与发布水位

- 每个数据集内部有一把锁，`append`（整批）与 `snapshot` 各自在锁内执行；
  不同数据集之间互不阻塞（注册表是并发 map，各自持各自的锁）。
- 一次批量写入是**原子发布**：整批记录全部加入后，已发布水位 `watermark` 才前移。
  读端永远看不到“写了一半”的批次，也观察不到 id 空洞。
- 记录 id 从 1 开始、连续不跳号；`snapshot` 返回防御性拷贝，遍历期间不受后续写入影响。

这提供的是**读已提交（read-committed）以上的快照语义**：一次查询读取开始时刻
`watermark` 之前已完整提交的数据。

### 6.2 为什么“边写边翻页”不会重复或丢数据

朴素的 offset 分页在数据持续增长时会出现漏读/重读。本服务用**固定水位快照**解决：

1. 第一次查询不传 `watermark`，服务端取当前已发布水位 `w`，并在响应中返回 `watermark = w`；
2. 后续翻页把同一个 `w` 回传（`"watermark": w`），服务端始终在 id `1..w` 的**不可变版本**上做
   过滤、排序与 offset 分页；
3. 遍历期间新写入的记录（id > w）不出现在本次遍历中；遍历结束后再发一次不带 watermark 的
   查询即可拿到新版本和新增数据。

因此即使写入与翻页完全并发：**同一次遍历内不重复、不丢失，顺序可复现**。
（若不回传 watermark，每页都读最新版本，则 offset 分页可能随并发写入漂移——这是调用方的选择，
默认推荐回传。）

### 6.3 可见性

- 一条/一批记录在写入响应返回后即对之后的新查询可见（响应里的 `toId/watermark` 即可作为可见边界）。
- 进行中的查询不会被后续写入影响（快照隔离）。
- 内存数据在进程重启后丢失；本服务不承诺持久性，仅用于本地/内部场景。

---

## 7. 测试与验证

```bash
mvn test              # 全量
mvn -Dtest=RecordValidatorTest test       # 单类
```

测试日志通过 `BusinessCoverageLogger`（JUnit `TestWatcher`，见 `src/test/.../support`）
逐条打印用例的业务名（`@DisplayName`）与 `✔ 通过 / ✘ 失败`，可直接看出覆盖了哪些业务及每个用例结果，例如：

```
INFO [BUSINESS-TEST] ▶ 开始用例 [Invalid] 必填字段缺失 -> missing_field ...
INFO [BUSINESS-TEST]   ✔ 通过 [必填字段缺失 -> missing_field ...]
```

覆盖的业务场景（共 51 个用例）：

| 测试类 | 覆盖业务 |
| --- | --- |
| `RecordValidatorTest` | 缺失/类型不匹配/未知字段三类错误可区分、同记录多问题全收集、批量下标定位、正常记录放行 |
| `SchemaCompatibilityTest` | 新增可选字段/放宽必填/重排兼容；删字段/改类型/收紧必填/新增必填拒绝 |
| `DatasetStoreTest` | 重名 409、未知数据集 404、连续 id、原子批次、快照区间与防御性拷贝 |
| `PaginationConcurrencyTest` | 8 线程并发写（8000 条）同时翻页：写入区间连续无重叠、每轮固定水位遍历不重不丢、最终恰好 1..N |
| `QueryEngineTest` | 过滤/选列/组合条件/稳定排序/分页、watermark 隔离、五类聚合与 null 语义、非法查询 400 |
| `DatasetApiTest` | HTTP 全链路：注册/演进/写入/校验 422/聚合/错误结构统一、底层解析异常不外泄 |
| `ApplicationTests` | Spring 上下文可启动 |

---

## 8. 代码结构

```
src/main/java/.../datasetqueryservicejava
├── model        FieldType / FieldDef / DatasetSchema / DatasetRecord（含服务端 id）
├── error        ErrorCode / ApiException / ApiError / ValidationIssue（统一错误模型）
├── validation   RecordValidator（三类校验）、SchemaCompatibility（兼容判定）
├── storage      Dataset（锁 + 水位 + 快照）、DatasetStore（注册表）
├── query        查询模型 + QueryEngine（过滤/选列/聚合/稳定排序/分页/查询校验）
└── web          DatasetController、GlobalExceptionHandler、DtoMapper、请求/响应 DTO
```

## 9. 已知边界

- 数据仅存内存，无持久化、无鉴权、无跨实例能力（按需求刻意为之）。
- 非聚合查询的默认排序键为 schema 首列 + 记录 id；暂不支持调用方自定义 `orderBy`。
- 单字段标量比较，暂不支持 OR / 嵌套条件 / 正则。
