# IoT 设备接入与设备影子服务（本地版）

基于 Spring Boot 3 / Java 21 的**本地运行**物联网设备接入与设备影子服务。
使用内存状态存储模拟设备遥测上报、在线状态与命令下发，目标是在**乱序、重复、
断连重连与并发更新**下，让设备状态收敛、规则触发结论确定且可解释。

## 1. 构建与运行

```bash
mvn spring-boot:run          # 启动，默认端口 8080
mvn test                     # 运行全部单测（含输入时间戳/判定依据/触发结果日志）
```

内置合法字段（`FieldSchemaRegistry`，可通过代码注册扩展）：

| 字段 | 类型 |
| --- | --- |
| temperature | 数值（归一化为 double） |
| humidity | 数值 |
| powerOn | 布尔 |
| firmwareVersion | 字符串 |

## 2. REST 接口

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/rules` | 注册阈值规则 |
| `POST /api/devices/{id}/connect` | 上线/重连，返回本次补发的命令 |
| `POST /api/devices/{id}/disconnect` | 标记离线（影子不清空） |
| `POST /api/devices/{id}/telemetry` | 遥测上报 |
| `POST /api/devices/{id}/shadow` | 版本化影子补丁（desired） |
| `GET  /api/devices/{id}/shadow` | 影子快照（reported / reportedTimestamps / desired / version / status） |

### 请求示例

```bash
# 注册规则：temperature > 30 时下发 cool-down
curl -X POST localhost:8080/api/rules -H 'Content-Type: application/json' -d '{
  "ruleId":"overheat","metric":"temperature","operator":"GT","threshold":30,
  "commandName":"cool-down","commandPayload":{"fan":"on"}}'

# 遥测上报（timestamp=设备侧采集时间 epochMillis，clientToken=幂等令牌）
curl -X POST localhost:8080/api/devices/d1/telemetry -H 'Content-Type: application/json' -d '{
  "timestamp":2000,"clientToken":"t1","metrics":{"temperature":35}}'

# 影子补丁（baseVersion 为客户端所基于的影子版本）
curl -X POST localhost:8080/api/devices/d1/shadow -H 'Content-Type: application/json' -d '{
  "baseVersion":0,"timestamp":4000,"clientToken":"s1","fields":{"powerOn":true}}'
```

`UpdateOutcome` 字段：`accepted`（是否通过校验，幂等命中也算通过）、`applied`
（是否实际改动状态）、`reason`（未生效/被拒绝原因）、`version`、`detail`（判定依据）、
`clientToken`。

## 3. 遥测上报与状态收敛规则

每个设备一把互斥锁（`DeviceStore.lockFor`），同一设备的“读-改-写-规则判定”串行化。

1. **幂等（重复请求不产生副作用）**：相同 `(telemetry, deviceId, clientToken)`
   的上报直接返回**首次结论**，不再次合并、不再次评估规则。
2. **整批校验（失败原因可区分，按固定优先级）**：
   - `UNKNOWN_FIELD`：存在未注册字段；
   - `TYPE_MISMATCH`：值类型与字段声明不兼容（如 temperature 传字符串）。
   校验不过则整批拒绝，状态不变。
3. **字段级 Last-Writer-Wins（乱序收敛）**：每个指标单独记录最近生效时间戳，
   仅当新时间戳**严格更大**才覆盖；更旧时间戳跳过（`applied=false`，
   `reason=NONE`，detail 注明“过期跳过”）。因此无论到达顺序如何，最终状态恒为
   “每个字段取最大时间戳对应的值”。
4. **同时间戳冲突**：相同时间戳出现不同值无法判定先后，整批拒绝
   `SAME_TIMESTAMP_CONFLICT`，不做任意覆盖。
5. 重复上报（同时间戳同值）不改动状态、不产生副作用。

## 4. 影子合并（desired）规则

补丁为**整批原子校验 + 原子合并**，任一不通过则版本与状态均不变，拒绝原因固定优先级：

| 原因 | 触发条件 |
| --- | --- |
| `STALE_VERSION` | `baseVersion` 落后于当前影子版本（乐观并发控制） |
| `UNKNOWN_FIELD` | 补丁含未注册字段（不静默丢弃） |
| `TYPE_MISMATCH` | 字段值类型不兼容 |
| `STALE_TIMESTAMP` | 补丁时间戳早于设备最近活动时间 |

通过后按字段合并进 desired（旧字段保留），版本号 `version += 1`。
相同 `clientToken` 的补丁重放返回首次结论（含拒绝结论），版本不重复递增，
因此**同一批更新任意次序、任意次数重放，最终状态与每次结论完全一致**。

## 5. 规则触发语义

- 规则形如 `metric OP threshold`（`GT/GE/LT/LE`），在每次**使状态生效**的遥测合并后、
  于同一把设备锁内评估。
- **边沿触发 + 武装(armed)去重**：条件由“假/缺失”变为“真”时下发一次命令并解除武装；
  条件持续为真期间的后续上报（含并发上报）不再下发；条件恢复为假后重新武装，
  再次变真才会第二次触发。
- 命令 ID 为 `ruleId|deviceId|触发指标的生效时间戳`，在 `CommandStore` 中幂等去重，
  并发下也不会产生第二条命令。
- **在线**：命令即时送达（`delivered=true`）；**离线**：命令入待发队列
  （`delivered=false`），不丢弃。
- **重连补发**：上线后按命令创建时间升序补发，`markDelivered` 为 CAS，
  每条命令只送达一次；重复重连不会重复执行。补发全程**不修改影子**，
  离线期间已收敛的较新状态不会回滚（离线到达的过期遥测同样被 LWW 拒绝）。

## 6. 验证方法与测试覆盖

`mvn test`，共 10 个用例（`src/test/.../tests`），日志中打印**输入时间戳、
判定依据与触发结果**：

- `TelemetryConvergenceTest`：乱序/重复/过期上报收敛、clientToken 重放一致、
  同时间戳冲突拒绝；
- `ShadowMergeTest`：四类拒绝原因可区分、版本递增、字段级合并、重放结论一致；
- `RuleTriggerTest`：持续为真只触发一次、假→真重新武装、16 线程并发只下发一条；
- `ReconnectionTest`：离线排队、重连按序补发一次、重复重连不重复执行、
  影子不回滚、离线过期数据不覆盖。
- `ApplicationTests`：Spring 上下文装配。

## 7. 关键类

| 类 | 职责 |
| --- | --- |
| `DeviceShadow` | 单设备影子：reported/字段时间戳/desired/version/status（仅锁内变更） |
| `DeviceStore` | 内存影子表 + 每设备 `ReentrantLock` |
| `IdempotencyStore` | clientToken → 首次结论 |
| `FieldSchemaRegistry` | 字段白名单与类型兼容/归一化 |
| `TelemetryService` | 遥测校验、LWW 收敛、同锁触发规则 |
| `ShadowUpdateService` | 版本化原子合并与拒绝分类 |
| `RuleEngine` | 阈值评估、armed 去重、在线下发/离线入队 |
| `CommandStore` | 命令幂等记录与补发队列（CAS 送达） |
| `RuleStateStore` | 每 (设备,规则) 的武装状态 |
| `DeviceConnectionService` | 上下线与重连补发 |
