package com.github.h3yow.iotdevicetwinjava.service;

import com.github.h3yow.iotdevicetwinjava.model.DeviceShadow;
import com.github.h3yow.iotdevicetwinjava.model.RejectReason;
import com.github.h3yow.iotdevicetwinjava.model.ShadowPatch;
import com.github.h3yow.iotdevicetwinjava.model.UpdateOutcome;
import com.github.h3yow.iotdevicetwinjava.store.DeviceStore;
import com.github.h3yow.iotdevicetwinjava.store.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 影子期望状态(desired)的版本化合并。
 *
 * 整批补丁按固定优先级原子校验，任一不通过则整体拒绝、状态与版本均不变：
 * 1. clientToken 幂等重放 -> 返回首次结论；
 * 2. baseVersion 落后于当前版本 -> STALE_VERSION（乐观并发控制）；
 * 3. 存在未知字段           -> UNKNOWN_FIELD（不静默丢弃）；
 * 4. 字段类型不匹配         -> TYPE_MISMATCH；
 * 5. 补丁时间戳过期         -> STALE_TIMESTAMP。
 *
 * 全部通过后：归一化字段 -> mergeDesired（版本 +1）。
 * 同一批补丁重放（相同令牌）结论与最终状态完全一致。
 */
@Service
public class ShadowUpdateService {

    private static final Logger log = LoggerFactory.getLogger(ShadowUpdateService.class);

    private final DeviceStore deviceStore;
    private final IdempotencyStore idempotencyStore;
    private final FieldSchemaRegistry schemaRegistry;

    public ShadowUpdateService(DeviceStore deviceStore,
                               IdempotencyStore idempotencyStore,
                               FieldSchemaRegistry schemaRegistry) {
        this.deviceStore = deviceStore;
        this.idempotencyStore = idempotencyStore;
        this.schemaRegistry = schemaRegistry;
    }

    public UpdateOutcome applyPatch(ShadowPatch patch) {
        String deviceId = patch.deviceId();
        ReentrantLock lock = deviceStore.lockFor(deviceId);
        lock.lock();
        try {
            return doApply(patch);
        } finally {
            lock.unlock();
        }
    }

    private UpdateOutcome doApply(ShadowPatch patch) {
        String deviceId = patch.deviceId();
        String token = patch.clientToken();

        log.info("[shadow] 收到补丁 device={} baseVersion={} timestamp={} token={} fields={}",
                deviceId, patch.baseVersion(), patch.timestamp(), token,
                new TreeMap<>(patch.fields()));

        if (token != null && !token.isBlank()) {
            var key = new IdempotencyStore.Key("shadow", deviceId, token);
            UpdateOutcome cached = idempotencyStore.get(key);
            if (cached != null) {
                log.info("[shadow] device={} token={} 幂等命中，返回首次结论 accepted={} applied={} reason={} version={}",
                        deviceId, token, cached.accepted(), cached.applied(), cached.reason(), cached.version());
                return cached;
            }
        }

        DeviceShadow shadow = deviceStore.getOrCreate(deviceId);

        if (patch.baseVersion() < shadow.getVersion()) {
            return finish(deviceId, token, reject(shadow, RejectReason.STALE_VERSION,
                    "baseVersion=" + patch.baseVersion() + " 落后于当前版本 " + shadow.getVersion()
                            + "；请基于最新影子重试。补丁 timestamp=" + patch.timestamp()));
        }

        for (String field : patch.fields().keySet()) {
            if (!schemaRegistry.isKnown(field)) {
                return finish(deviceId, token, reject(shadow, RejectReason.UNKNOWN_FIELD,
                        "未知字段 '" + field + "'，合法字段=" + schemaRegistry.knownFields()
                                + "；baseVersion=" + patch.baseVersion()));
            }
        }
        for (Map.Entry<String, Object> e : patch.fields().entrySet()) {
            if (!schemaRegistry.isTypeCompatible(e.getKey(), e.getValue())) {
                return finish(deviceId, token, reject(shadow, RejectReason.TYPE_MISMATCH,
                        "字段 '" + e.getKey() + "' 类型不匹配，值=" + e.getValue()
                                + "(" + (e.getValue() == null ? "null" : e.getValue().getClass().getSimpleName())
                                + ")；baseVersion=" + patch.baseVersion()));
            }
        }

        if (patch.timestamp() < shadow.getLastSeenTimestamp()) {
            return finish(deviceId, token, reject(shadow, RejectReason.STALE_TIMESTAMP,
                    "补丁 timestamp=" + patch.timestamp() + " 早于设备最近活动时间 "
                            + shadow.getLastSeenTimestamp() + "，过期补丁不得覆盖较新状态"));
        }

        // 归一化后原子合并，版本 +1
        Map<String, Object> normalized = new LinkedHashMap<>();
        patch.fields().forEach((k, v) -> normalized.put(k, schemaRegistry.normalize(k, v)));
        int newVersion = shadow.mergeDesired(normalized);

        UpdateOutcome outcome = new UpdateOutcome(true, true, RejectReason.NONE, newVersion,
                String.format("合并成功 baseVersion=%d -> newVersion=%d fields=%s timestamp=%d",
                        patch.baseVersion(), newVersion, new TreeMap<>(normalized), patch.timestamp()),
                token);
        log.info("[shadow] device={} 结论 accepted=true applied=true reason=NONE version={} desired={}",
                deviceId, newVersion, shadow.getDesired());
        return finish(deviceId, token, outcome);
    }

    private UpdateOutcome reject(DeviceShadow shadow, RejectReason reason, String detail) {
        log.warn("[shadow] device={} 拒绝 reason={} 当前version={} | {}",
                shadow.getDeviceId(), reason, shadow.getVersion(), detail);
        return new UpdateOutcome(false, false, reason, shadow.getVersion(), detail, null);
    }

    private UpdateOutcome finish(String deviceId, String token, UpdateOutcome outcome) {
        if (token != null && !token.isBlank()) {
            var key = new IdempotencyStore.Key("shadow", deviceId, token);
            idempotencyStore.put(key, outcome);
        }
        return outcome;
    }
}
