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
mvn test                     # 运行全部单测（28 个用例）
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

# 6) 批量期望配置下发：一份配置发给一批设备，逐台返回结论（batchId 幂等）
curl -X POST localhost:8080/api/shadows/batch-update -H 'Content-Type: application/json' -d '{
  "batchId":"b-20261003-01","deviceIds":["dev1","dev2","dev3"],
  "timestamp":160,"desired":{"mode":"cool","enabled":true}}'
```

接口一览：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/devices/{id}/register` | 注册设备（幂等），默认 schema |
| GET  | `/api/devices/{id}/shadow` | 查询设备影子不可变快照 |
| POST | `/api/devices/{id}/telemetry` | 遥测上报（LWW 收敛 + 幂等 + 规则评估） |
| POST | `/api/devices/{id}/online` `/offline` `/reconnect` | 生命周期与补发 |
| POST | `/api/shadows/update` | desired 区版本化合并（单台，乐观版本） |
| POST | `/api/shadows/batch-update` | 批量期望配置下发（一份配置 → 一批设备，逐台结论） |
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

### 3.3 批量期望配置下发（`BatchDispatchService`）

`POST /api/shadows/batch-update`，请求体：

```json
{
  "batchId": "b-20261003-01",
  "deviceIds": ["dev1", "dev2", "dev3"],
  "timestamp": 160,
  "desired": {"mode": "cool", "enabled": true}
}
```

一份 `desired` 配置发给清单内所有设备，响应**逐台返回结论**：

- `accepted=true` 表示**整批被受理**，不代表每台成功；每台成败看 `devices[]`：
  - `devices[i].accepted`：该台是否生效；
  - `devices[i].reason`：整台级拒绝原因（如 `DEVICE_NOT_REGISTERED`）；
  - `devices[i].result`：该台完整的影子更新结论——`newVersion/currentVersion`、
    `idempotentReplay` 与逐字段 `fields[]`（字段级拒绝原因，如 `UNKNOWN_FIELD`、
    `TYPE_MISMATCH`、`STALE_VERSION`），与单台接口返回结构一致。
- 顶层 `successCount / rejectedCount` 汇总，`devices[]` 顺序与请求 `deviceIds` 一致。

**逐台隔离**

1. 每台设备在**各自设备锁内独立判定**，由固定大小线程池并发处理：
   一台设备慢，只占它自己的任务，不会卡住整批；一台被拒或执行异常，
   不影响其他设备已经生效的结果。
2. 结论、版本、命令严格按设备隔离：批量派生的单台幂等键为
   `updateId = "batch:" + batchId + ":" + deviceId`，ShadowService 的幂等记忆
   按 `(deviceId, updateId)` 作用域保存，任何判定结论与版本都不会串到另一台。
3. 单台执行抛异常被隔离为该台 `DEVICE_DISPATCH_FAILED`，不会让整批失败。

**与单台下发的关系**

- 批量路径直接复用 `ShadowService` 的同一份“版本校验 → schema 校验 → 提交/拒绝 →
  记忆 → 规则触发”逻辑（`applyUpdateOnCurrentVersion`），两条路的字段/类型/版本判定
  口径完全一致。
- 区别只在版本号来源：单台接口要求调用方携带 `baseVersion` 做乐观锁；
  **批量接口在设备锁内取该台当前版本**作为基线，因此调用方不需要先逐台读版本，
  多个批次并发到达同一设备时由设备锁串行合并，不会产生无谓的 `STALE_VERSION`，
  也不会丢更新。
- 单台与批量对同一台设备重放同一份内容：都不会重复写状态、不会重复下发命令，
  各自返回首次结论（见 `BatchDispatchTest.singleAndBatchPathsAgreeOnReplayOfSameContent`）。

**批次幂等重放与标识冲突**

- 幂等键是 `batchId`；内容签名 = `deviceIds 清单(顺序敏感) + timestamp + 排序后的 desired`。
- **同 `batchId` + 同内容**重放（网关超时重试）：直接返回首次的逐台结论
  （顶层 `idempotentReplay=true`，首次被拒的设备也原样回放），**不重复写状态、
  不重复下发命令**，结论逐字节确定，不会出现“返回成功但过一会儿又变了”。
- **同 `batchId` 但内容不同**（换了设备清单、时间戳或期望配置）：整批以
  `BATCH_ID_CONFLICT` 拒绝，明确告知该标识已被别的请求用过，**不覆盖已有结论、
  不写一半**。
- 整批结论在 `batchId` 级锁内“先记忆后返回”，并发的同 `batchId` 请求要么等待首次、
  要么看到完整结论；不同 `batchId` 的批次互不阻塞。

**容量上限与兼容范围**

- 单批设备数上限默认 **500 台**，可通过属性 `iot.batch.max-size` 覆盖；
  超限整批以 `BATCH_TOO_LARGE` 拒绝，**一台都不处理**（不会只处理一部分）。
- 设备清单为空、`desired` 为空、`batchId` 缺失、清单含重复设备，均整批
  `BATCH_INVALID`（结构性非法请求不落记忆，允许修正后用原 batchId 重试）。
- 实测并发：单批 300 台全部逐台生效、结论顺序与清单一致；12 个批次并发且共享
  同一台设备时，共享设备版本恰好串行推进 12 次、无重复无丢失（见测试）。

### 3.4 可区分的失败原因（`RejectReason`）

| 原因 | 含义 |
|---|---|
| `STALE_TIMESTAMP` | 遥测字段时间戳早于已保存时间戳（乱序过期） |
| `DUPLICATE_REPORT` | 同时间戳同值 / 整批重复，幂等忽略 |
| `SAME_TIMESTAMP_CONFLICT` | 同时间戳但值不同，无法判定先后 |
| `UNKNOWN_FIELD` | 字段不在设备 schema 允许集合内 |
| `STALE_VERSION` | 影子更新 baseVersion 落后/偏离当前版本 |
| `TYPE_MISMATCH` | 值类型与 schema 声明不兼容 |
| `UPDATE_ID_CONFLICT` | 幂等键 updateId 被不同请求体复用 |
| `DEVICE_NOT_REGISTERED` | 批量/单台下发的目标设备未注册（逐台拒绝，不影响其他设备） |
| `DEVICE_DISPATCH_FAILED` | 单台执行异常，隔离为该台失败 |
| `BATCH_TOO_LARGE` | 批量设备数超过单批上限，整批拒绝、不处理任何一台 |
| `BATCH_ID_CONFLICT` | 同一 batchId 被不同内容复用，拒绝覆盖 |
| `BATCH_INVALID` | 批量请求结构性非法（缺 batchId、空清单/空配置、重复设备） |

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
| `RuleTriggerConcurrencyTest` | 16 线程并发同一上升沿只下发一次、持续条件不重复触发、升降沿再触发、多设备并发 |
| `OfflineReconnectTest` | 离线积压、重连升序补发一次、重复重连不重复、补发不回滚版本/状态、离线期乱序与重放语义 |
| `HttpEndToEndTest` | MockMvc 全链路：注册/上线/规则/乱序上报/离线重连/影子更新拒绝/批量下发·重放·标识冲突 |
| `BatchDispatchTest` | 批量逐台隔离、UNKNOWN_FIELD/TYPE_MISMATCH/未注册逐台拒绝、批次重放结论一致无重复副作用、拒绝结论也稳定回放、batchId 换内容 BATCH_ID_CONFLICT、超容量整批拒绝、非法批次、300 台大批量、12 批并发共享设备不踩踏、单台与批量结论一致 |
| `ApplicationTests` | Spring 上下文装配冒烟 |

日志样例（`target/surefire-reports/TEST-*.xml` 的 `system-out`）：

```
[遥测上报] device=dev-A reportId=p10 输入时间戳=10 fields={temperature=10.0}
[遥测结论] device=dev-A version=2 判定依据=逐字段LWW/幂等判定 触发结果=字段接受0 拒绝1 ...
[规则触发] device=dev-C rule=r-hot 输入时间戳=115 field=temperature mergedValue=30.0015 ... => fired=true 命令=cmd-dev-C-r-hot-v1 下发方式=IMMEDIATE
[重连补发] device=dev-E 命令=cmd-dev-E-r-hot-v3 判定依据=离线积压且未下发过 触发结果=补发一次(RECONNECT)，状态不回滚
[批量逐台] batchId=b-20261003-01 device=dev2 判定依据=当前版本校验通过且字段合法 最终结果=生效@v1
[批量重放] batchId=b-20261003-01 判定依据=batchId+内容一致 触发结果=回放首次逐台结论，无设备副作用、不重复下发命令
```

本地复现批量语义：

```bash
mvn test -Dtest=BatchDispatchTest            # 只跑批量 10 个用例
mvn test                                      # 全量 28 个用例，应全部通过
# 日志位置：target/surefire-reports/TEST-*BatchDispatchTest.xml 的 system-out，
# 可按 batchId / device 追溯每台设备的判定依据与最终结果
```

每条结论都可从日志追溯：**输入是什么（含时间戳）→ 依据什么规则判定 → 产生了什么结果**。

## 6. 目录结构

```
model/     枚举与不可变记录（影子、字段值、上报、更新、批量下发、规则、命令、各类结论）
store/     DeviceShadowStore：内存状态 + 每设备锁 + 事务原语
service/   TelemetryService / ShadowService / BatchDispatchService / RuleEngine / DeviceLifecycleService
web/       DeviceController / ShadowController / BatchController / RuleAdminController
config/    AppConfig（本地内存 Bean 装配；iot.batch.max-size 配置单批容量）
```
