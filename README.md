# 物联网设备接入与设备影子服务（本地版）

一套可在本地运行、状态保存在内存中的 IoT 设备接入 + 设备影子演示服务。
核心目标：在**乱序上报、重复上报、断连重连、并发更新**下，设备状态与规则触发结论
**确定、收敛、可解释、可重放**。

- Java 21 / Spring Boot 3.4 / 纯内存状态（`DeviceShadowStore`），无需数据库与消息队列
- 每台设备一把锁，同一设备的“读-判定-写-触发”原子串行，跨设备互不阻塞
- 所有拒绝都带**可区分的原因枚举**（`RejectReason`），不静默丢弃
- 单测日志统一打印：**输入时间戳、判定依据、触发结果**

---

## 1. 快速开始

```bash
mvn spring-boot:run          # 启动，默认 8080 端口
mvn test                     # 运行全部单测（17+ 用例）
```

典型 HTTP 流程：

```bash
# 1) 注册并上线（默认 schema: temperature/humidity NUMBER, mode STRING, enabled BOOLEAN）
curl -X POST localhost:8080/api/devices/dev1/register -H 'Content-Type: application/json' -d '{}'
curl -X POST localhost:8080/api/devices/dev1/online   -H 'Content-Type: application/json' -d '{"timestamp":1}'

# 2) 配置阈值规则：temperature >= 30 触发 COOL_ON
curl -X POST localhost:8080/api/rules -H 'Content-Type: application/json' -d '{
  "ruleId":"r-hot","deviceId":"dev1","field":"temperature",
  "operator":"GTE","threshold":30.0,"commandType":"COOL_ON","commandPayload":{"fan":"high"}}'

# 3) 遥测上报（乱序/重复由服务端收敛）
curl -X POST localhost:8080/api/devices/dev1/telemetry -H 'Content-Type: application/json' -d '{
  "deviceId":"dev1","timestamp":100,"reportId":"p100","fields":{"temperature":31.5}}'

# 4) 查询影子 / 离线 / 重连补发
curl localhost:8080/api/devices/dev1/shadow
curl -X POST localhost:8080/api/devices/dev1/offline   -H 'Content-Type: application/json' -d '{"timestamp":101}'
curl -X POST localhost:8080/api/devices/dev1/reconnect -H 'Content-Type: application/json' -d '{"timestamp":200}'

# 5) 期望区版本化合并（baseVersion 为读到的当前版本）
curl -X POST localhost:8080/api/shadows/update -H 'Content-Type: application/json' -d '{
  "deviceId":"dev1","baseVersion":1,"updateId":"u1","timestamp":150,"desired":{"mode":"cool"}}'

# 6) 批量期望配置下发：同一份 desired 一次发给一批设备，逐台返回结论
curl -X POST localhost:8080/api/shadows/batch-update -H 'Content-Type: application/json' -d '{
  "batchId":"batch-001","baseVersion":1,"timestamp":160,
  "desired":{"mode":"cool"},"deviceIds":["dev1","dev2","dev3"]}'
```

接口一览：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/devices/{id}/register` | 注册设备（幂等），默认 schema |
| GET  | `/api/devices/{id}/shadow` | 查询设备影子不可变快照 |
| POST | `/api/devices/{id}/telemetry` | 遥测上报（LWW 收敛 + 幂等 + 规则评估） |
| POST | `/api/devices/{id}/online` `/offline` `/reconnect` | 生命周期与补发 |
| POST | `/api/shadows/update` | desired 区版本化合并（单台） |
| POST | `/api/shadows/batch-update` | desired 区批量下发（同一份配置发一批设备，逐台结论） |
| POST/GET | `/api/rules` | 阈值规则管理/查询 |

---

## 2. 影子模型

`DeviceShadow` 为不可变快照（copy-on-write），包含：

- `version`：**数据版本**，仅在 reported/desired 真正写入成功时单调 +1；
  上下线、规则触发、命令补发都**不**改变数据版本。
- `reported`：设备上报区。每个字段是 `TaggedValue(value, timestamp, source)`，
  时间戳跟随字段保存，作为新旧判定依据。
- `desired`：期望区（应用端版本化合并写入）。
- 合并视图（规则读取口径）：`desired` 优先，缺省回退 `reported`。
- `status` / `lastSeenTs`：在线状态与最近有效上报时间。
- `pendingCommands` / `deliveredCommands` / `deliveredDedupKeys`：
  离线积压、已下发历史、命令去重键。
- `processedReportIds` / `processedReportFingerprints` / `processedUpdates`：
  遥测整批与影子更新的幂等记忆，保证重放结论一致。
- `ruleStates`：每条规则上一次评估时条件是否成立，支撑边沿触发。

---

## 3. 遥测上报与影子合并规则

### 3.1 遥测上报（`TelemetryService`）

同一设备全程在设备锁内串行处理，逐字段判定：

1. **整批幂等**：优先按 `reportId` 判重；没有 `reportId` 时按
   `SHA-256(timestamp | 字段名排序后的键值)` 内容指纹判重。命中则返回
   `idempotentReplay=true`，**不写状态、不评估规则、不产生命令**
   （重复上报不会重复累计、不会重复副作用）。字段顺序不同但内容相同也算重复。
2. **按字段 LWW（last-writer-wins）**：`incomingTs > currentTs` 才覆盖；
   `incomingTs < currentTs` 以 `STALE_TIMESTAMP` 拒绝——**过期时间戳不得覆盖较新状态**。
3. **同时间戳**：值相等 → `DUPLICATE_REPORT`（幂等忽略，无写入、无版本增长）；
   值不同 → `SAME_TIMESTAMP_CONFLICT`（先后无法确定，拒绝而不是随机挑一个）。
4. **schema 校验**：未知字段 → `UNKNOWN_FIELD`；类型不匹配 → `TYPE_MISMATCH`。
5. 至少一个字段被新应用才 `version + 1`，随后驱动一次规则评估。

乱序只影响**到达顺序**，不影响最终状态：任何乱序/重复序列重复执行，最终影子一致。

### 3.2 期望区版本化合并（`ShadowService`）

原子批量处理：

1. **乐观版本**：`baseVersion` 必须等于当前 `version`，否则整批以
   `STALE_VERSION` 拒绝（响应带当前版本供客户端重读），**不做部分写入**。
2. **schema 兼容**：逐字段校验，未知字段 `UNKNOWN_FIELD`、类型不符
   `TYPE_MISMATCH`，与版本失败原因可区分；存在非法字段则整批不提交。
3. **幂等重放**：相同 `updateId` + 相同请求体 → 返回首次结论
   （`idempotentReplay=true`），不重复写入、不重复触发；
   相同 `updateId` 但**不同请求体** → `UPDATE_ID_CONFLICT` 拒绝。
   被拒绝的批次结论同样被记忆，重放得到相同拒绝结论。
4. 成功提交后 `version + 1` 并驱动规则评估。

### 3.3 可区分的失败原因（`RejectReason`）

| 原因 | 含义 |
|---|---|
| `STALE_TIMESTAMP` | 遥测字段时间戳早于已保存时间戳（乱序过期） |
| `DUPLICATE_REPORT` | 同时间戳同值 / 整批重复，幂等忽略 |
| `SAME_TIMESTAMP_CONFLICT` | 同时间戳但值不同，无法判定先后 |
| `UNKNOWN_FIELD` | 字段不在设备 schema 允许集合内 |
| `STALE_VERSION` | 影子更新 baseVersion 落后/偏离当前版本 |
| `TYPE_MISMATCH` | 值类型与 schema 声明不兼容 |
| `UPDATE_ID_CONFLICT` | 幂等键 updateId 被不同请求体复用 |
| `DEVICE_NOT_FOUND` | 批量下发清单中的设备未注册（逐台拒绝，不影响其他设备） |
| `BATCH_TOO_LARGE` | 批量设备数超过单批上限（500），整批拒绝，不处理任何子集 |
| `EMPTY_BATCH` | 批量设备清单为空，整批拒绝 |
| `BATCH_ID_CONFLICT` | 批次标识 batchId 已被另一份内容的批量请求占用 |

---

### 3.4 批量期望配置下发（`BatchShadowService`）

`POST /api/shadows/batch-update` 把**同一份 desired** 一次发给一批设备，
请求体：`batchId`（批次标识/幂等键）、`baseVersion`（对清单中每台设备统一使用的期望基准版本）、
`timestamp`、`desired`、`deviceIds`（设备清单）。

**逐台隔离**：每台设备独立走与单台接口完全相同的判定链路（`ShadowService`，设备锁内串行），
返回的 `deviceResults` 按 deviceId 排序，逐台给出 `committed` / 拒绝原因
（`UNKNOWN_FIELD`、`TYPE_MISMATCH`、`STALE_VERSION`、`DEVICE_NOT_FOUND` 互相可区分）。
某台失败不影响其他台已成功的结果；每台结论里的版本号都是该台自己的版本，不会串台。
批次内重复出现的设备会被去重，只判定一次。

**与单台下发的关系**：批量对每台设备以 `batchId` 作为该设备的 `updateId` 走同一条
`ShadowService` 判定与幂等记忆链路。此外 `ShadowService` 还有**内容级重放**判定：
`updateId` 不同、但请求体签名（baseVersion+timestamp+desired）与本设备已处理的某次更新
完全一致时，回放那次结论（`idempotentReplay=true`）。因此单台接口与批量接口对同一台设备
重放同一份内容，结论一致，不重复写状态、不重复下发命令。

**幂等重放与标识冲突**：
- 同 `batchId` + 同内容重发（含网关超时后的并发重试）：整批只执行一次，
  所有调用方拿到与首次**一模一样**的逐台结论（重放时 `idempotentReplay=true`，
  包括首次就被拒的那些台的拒绝结论），不重复写状态、不重复下发命令。
- 同 `batchId` 换了内容（desired、baseVersion、timestamp 或设备清单任一不同）：
  整批以 `BATCH_ID_CONFLICT` 拒绝，不覆盖已存结论、不写任何设备；已存结论之后仍可正常重放。
- 批次级校验失败（超限/空清单）的请求**不占用** batchId，修正规模后可用同一标识重发。

**容量上限与并发**：单批设备数上限 **500**（`BatchShadowService.MAX_BATCH_SIZE`），
超过或为空时整批拒绝（`BATCH_TOO_LARGE` / `EMPTY_BATCH`），不处理任何子集。
批内不同设备由线程池并行处理，单台处理慢不会卡住整批；同一台设备上的多个批次
靠设备锁串行，不会出现两个批次互相踩踏——不同内容的批次竞争同一台设备时，
先到者生效、后到者按 `STALE_VERSION` 拒绝，版本单调推进、结论确定。

**本地复现验证**：

```bash
mvn test -Dtest=BatchShadowUpdateTest   # 批量语义单测（逐台隔离/重放/冲突/并发/大批量/跨路径）
mvn test                                # 全部单测（含既有用例，不应回归）
# 日志中可看到批次标识、每台设备的判定依据与最终结果，例如：
# [批量逐台] batchId=b-iso device=dev-stale 判定依据=[*:STALE_VERSION] 触发结果=拒绝(currentVersion=1)
# [批量结论] batchId=b-iso ... 触发结果=生效 2 台，拒绝 3 台，明细=[dev-ok1=生效@v1, ...]
```

手工验证：按第 1 节启动服务后，用上面第 6 条 curl 下发一批；原样重发得到
`idempotentReplay=true` 且逐台结论不变；改 `desired` 重发得到 `BATCH_ID_CONFLICT`；
`deviceIds` 超过 500 台得到 `BATCH_TOO_LARGE`。

---

## 4. 规则触发语义（`RuleEngine`）

- 规则形如：`field operator threshold` 成立时产生一条命令（`GT/GTE/LT/LTE/EQ`）。
- **边沿触发**：仅在条件由“不成立（或首次未知）→ 成立”的**上升沿**产生命令；
  条件持续满足期间的后续更新**不重复下发**；条件解除后再次满足才会再触发。
- **去重键兜底**：`dedupKey = ruleId@version`。同一规则在同一数据版本上至多一条命令，
  与边沿状态构成双重防护；即使多线程同时满足条件，设备锁串行化后也只有一次下发。
- 命令 ID 由 `(deviceId, ruleId, version)` 确定性派生，规则按 `ruleId` 有序评估，
  因此同一状态重放得到同一命令集合。
- **在线**：命令立即 `DELIVERED(IMMEDIATE)`；
  **离线**：命令进入 `PENDING` 积压。

### 断连与重连（`DeviceLifecycleService`）

- 上线/离线只改 `status`，不动 `version` 与任何字段值。
- 重连时按 `triggerVersion` 升序补发积压命令，每条置 `DELIVERED(RECONNECT)` 并登记 dedupKey；
  已下发过的键跳过。
- 补发**不触碰 reported/desired、不增长版本**——因此“离线恢复后的补发不会造成状态回滚”；
  重复调用重连也不会重复执行。

---

## 5. 验证方法

```bash
mvn test
```

测试与覆盖场景：

| 测试类 | 覆盖内容 |
|---|---|
| `TelemetryConvergenceTest` | 乱序收敛、过期拒绝、整批幂等（reportId/内容指纹）、同时间戳冲突、未知字段/类型不匹配、重放确定性 |
| `ShadowMergeTest` | STALE_VERSION 整批拒绝、UNKNOWN_FIELD/TYPE_MISMATCH 可区分、更新重放一致、UPDATE_ID_CONFLICT、拒绝批次重放 |
| `BatchShadowUpdateTest` | 批量下发：逐台隔离与可区分原因、批次重放结论一致、batchId 换内容 BATCH_ID_CONFLICT、超限/空清单整批拒绝且不占用标识、300 台大批量与多批次并发不踩踏、单台/批量跨路径重放一致 |
| `RuleTriggerConcurrencyTest` | 16 线程并发同一上升沿只下发一次、持续条件不重复触发、升降沿再触发、多设备并发 |
| `OfflineReconnectTest` | 离线积压、重连升序补发一次、重复重连不重复、补发不回滚版本/状态、离线期乱序与重放语义 |
| `HttpEndToEndTest` | MockMvc 全链路：注册/上线/规则/乱序上报/离线重连/影子更新拒绝/批量下发（含重放与 batchId 冲突） |
| `ApplicationTests` | Spring 上下文装配冒烟 |

日志样例（`target/surefire-reports/TEST-*.xml` 的 `system-out`）：

```
[遥测上报] device=dev-A reportId=p10 输入时间戳=10 fields={temperature=10.0}
[遥测结论] device=dev-A version=2 判定依据=逐字段LWW/幂等判定 触发结果=字段接受0 拒绝1 ...
[规则触发] device=dev-C rule=r-hot 输入时间戳=115 field=temperature mergedValue=30.0015 ... => fired=true 命令=cmd-dev-C-r-hot-v1 下发方式=IMMEDIATE
[重连补发] device=dev-E 命令=cmd-dev-E-r-hot-v3 判定依据=离线积压且未下发过 触发结果=补发一次(RECONNECT)，状态不回滚
```

每条结论都可从日志追溯：**输入是什么（含时间戳）→ 依据什么规则判定 → 产生了什么结果**。

## 6. 目录结构

```
model/     枚举与不可变记录（影子、字段值、上报、更新、规则、命令、各类结论）
store/     DeviceShadowStore：内存状态 + 每设备锁 + 事务原语
service/   TelemetryService / ShadowService / BatchShadowService / RuleEngine / DeviceLifecycleService
web/       DeviceController / ShadowController / RuleAdminController
config/    AppConfig（本地内存 Bean 装配）
```
