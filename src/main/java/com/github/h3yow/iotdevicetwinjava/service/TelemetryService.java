package com.github.h3yow.iotdevicetwinjava.service;

import com.github.h3yow.iotdevicetwinjava.model.Command;
import com.github.h3yow.iotdevicetwinjava.model.DeviceShadow;
import com.github.h3yow.iotdevicetwinjava.model.RejectReason;
import com.github.h3yow.iotdevicetwinjava.model.TelemetryReport;
import com.github.h3yow.iotdevicetwinjava.model.UpdateOutcome;
import com.github.h3yow.iotdevicetwinjava.store.DeviceStore;
import com.github.h3yow.iotdevicetwinjava.store.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 遥测上报处理。
 *
 * 处理顺序（全程在设备锁内，结论确定可复现）：
 * 1. clientToken 幂等：命中则原样返回首次结论，不累计、不触发副作用；
 * 2. 整批字段校验：未知字段 -> UNKNOWN_FIELD；类型不匹配 -> TYPE_MISMATCH；
 *    整批拒绝，不改动状态；
 * 3. 字段级 LWW 预检：同时间戳但值冲突 -> SAME_TIMESTAMP_CONFLICT，整批拒绝；
 * 4. 逐字段合并：时间戳严格更大才覆盖(1)，相同(重复,0)或更旧(-1)均不覆盖；
 *    只要有一个字段生效即 applied=true；
 * 5. 合并后在同一把锁内评估规则，保证并发更新下不重复下发。
 *
 * 乱序上报最终收敛到“每字段取最大时间戳对应值”，与到达顺序无关。
 */
@Service
public class TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);

    private final DeviceStore deviceStore;
    private final IdempotencyStore idempotencyStore;
    private final FieldSchemaRegistry schemaRegistry;
    private final RuleEngine ruleEngine;

    public TelemetryService(DeviceStore deviceStore,
                            IdempotencyStore idempotencyStore,
                            FieldSchemaRegistry schemaRegistry,
                            RuleEngine ruleEngine) {
        this.deviceStore = deviceStore;
        this.idempotencyStore = idempotencyStore;
        this.schemaRegistry = schemaRegistry;
        this.ruleEngine = ruleEngine;
    }

    public UpdateOutcome report(TelemetryReport report) {
        String deviceId = report.deviceId();
        ReentrantLock lock = deviceStore.lockFor(deviceId);
        lock.lock();
        try {
            return doReport(report);
        } finally {
            lock.unlock();
        }
    }

    private UpdateOutcome doReport(TelemetryReport report) {
        String deviceId = report.deviceId();
        long ts = report.timestamp();
        String token = report.clientToken();

        log.info("[telemetry] 收到上报 device={} timestamp={} token={} metrics={}",
                deviceId, ts, token, new TreeMap<>(report.metrics()));

        // 1. 幂等重放
        if (token != null && !token.isBlank()) {
            var key = new IdempotencyStore.Key("telemetry", deviceId, token);
            UpdateOutcome cached = idempotencyStore.get(key);
            if (cached != null) {
                log.info("[telemetry] device={} token={} 幂等命中，返回首次结论 accepted={} applied={} reason={}",
                        deviceId, token, cached.accepted(), cached.applied(), cached.reason());
                return cached;
            }
        }

        DeviceShadow shadow = deviceStore.getOrCreate(deviceId);

        // 2. 整批字段校验：先未知字段，再类型不匹配（原因可区分、优先级固定）
        for (String field : report.metrics().keySet()) {
            if (!schemaRegistry.isKnown(field)) {
                return finish(deviceId, token, reject(shadow, RejectReason.UNKNOWN_FIELD,
                        "未知字段 '" + field + "'，合法字段=" + schemaRegistry.knownFields()
                                + "；输入 timestamp=" + ts));
            }
        }
        for (Map.Entry<String, Object> e : report.metrics().entrySet()) {
            if (!schemaRegistry.isTypeCompatible(e.getKey(), e.getValue())) {
                return finish(deviceId, token, reject(shadow, RejectReason.TYPE_MISMATCH,
                        "字段 '" + e.getKey() + "' 类型不匹配，值=" + e.getValue()
                                + "(" + (e.getValue() == null ? "null" : e.getValue().getClass().getSimpleName())
                                + ")；输入 timestamp=" + ts));
            }
        }

        // 3. LWW 预检：同时间戳不同值属于无法判定先后的冲突，整批拒绝
        List<int[]> codes = new ArrayList<>();
        List<Map.Entry<String, Object>> entries = new ArrayList<>(report.metrics().entrySet());
        for (Map.Entry<String, Object> e : entries) {
            Object normalized = schemaRegistry.normalize(e.getKey(), e.getValue());
            Long oldTs = shadow.getReportedTimestamp(e.getKey());
            int code;
            if (oldTs == null) {
                code = 1;
            } else if (ts > oldTs) {
                code = 1;
            } else if (ts == oldTs) {
                code = java.util.Objects.equals(shadow.getReportedValue(e.getKey()), normalized) ? 0 : -2;
            } else {
                code = -1;
            }
            codes.add(new int[]{code});
        }
        for (int i = 0; i < entries.size(); i++) {
            if (codes.get(i)[0] == -2) {
                Map.Entry<String, Object> e = entries.get(i);
                return finish(deviceId, token, reject(shadow, RejectReason.SAME_TIMESTAMP_CONFLICT,
                        "字段 '" + e.getKey() + "' 在相同时间戳 " + ts + " 出现不同值：已有="
                                + shadow.getReported().get(e.getKey()) + " 新值=" + e.getValue()
                                + "；拒绝以避免不确定的覆盖"));
            }
        }

        // 4. 逐字段合并
        int updated = 0;
        int skippedStale = 0;
        int duplicate = 0;
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<String, Object> e = entries.get(i);
            Object normalized = schemaRegistry.normalize(e.getKey(), e.getValue());
            int code = codes.get(i)[0];
            int applied = shadow.putReported(e.getKey(), normalized, ts);
            if (code == 1 || applied == 1) {
                updated++;
                log.info("[telemetry] device={} 字段 {} 生效 <- {} @ ts={}", deviceId, e.getKey(), normalized, ts);
            } else if (code == -1) {
                skippedStale++;
                log.info("[telemetry] device={} 字段 {} 过期跳过: 输入ts={} < 已有ts={}",
                        deviceId, e.getKey(), ts, shadow.getReportedTimestamp(e.getKey()));
            } else {
                duplicate++;
                log.info("[telemetry] device={} 字段 {} 重复无变化 @ ts={}", deviceId, e.getKey(), ts);
            }
        }

        boolean applied = updated > 0;
        if (applied) {
            shadow.touch(ts);
        }

        // 5. 同一把锁内评估规则
        List<Command> fired = applied ? ruleEngine.evaluate(shadow) : List.of();

        String detail;
        if (applied) {
            detail = String.format("生效字段=%d 过期跳过=%d 重复=%d timestamp=%d；触发命令=%d %s",
                    updated, skippedStale, duplicate, ts, fired.size(),
                    fired.stream().map(c -> c.name() + "(delivered=" + c.delivered() + ")").toList());
        } else if (skippedStale > 0) {
            detail = String.format("全部字段过期或重复，状态未改动: 过期=%d 重复=%d timestamp=%d",
                    skippedStale, duplicate, ts);
        } else {
            detail = String.format("重复上报，状态未改动: 重复字段=%d timestamp=%d", duplicate, ts);
        }
        UpdateOutcome outcome = new UpdateOutcome(true, applied, RejectReason.NONE,
                shadow.getVersion(), detail, token);
        log.info("[telemetry] device={} 结论 accepted=true applied={} reason=NONE version={} | {}",
                deviceId, applied, shadow.getVersion(), detail);
        return finish(deviceId, token, outcome);
    }

    private UpdateOutcome reject(DeviceShadow shadow, RejectReason reason, String detail) {
        log.warn("[telemetry] device={} 拒绝 reason={} | {}", shadow.getDeviceId(), reason, detail);
        return new UpdateOutcome(false, false, reason, shadow.getVersion(), detail, null);
    }

    private UpdateOutcome finish(String deviceId, String token, UpdateOutcome outcome) {
        if (token != null && !token.isBlank()) {
            // 首次结论（含拒绝结论）固定下来：相同令牌重放始终得到同一结论，
            // 既不重复累计，也不重复触发副作用。
            var key = new IdempotencyStore.Key("telemetry", deviceId, token);
            idempotencyStore.put(key, outcome);
        }
        return outcome;
    }
}
