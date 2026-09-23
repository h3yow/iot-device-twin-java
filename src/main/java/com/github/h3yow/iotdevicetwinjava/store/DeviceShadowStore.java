package com.github.h3yow.iotdevicetwinjava.store;

import com.github.h3yow.iotdevicetwinjava.model.DeviceRegistration;
import com.github.h3yow.iotdevicetwinjava.model.DeviceShadow;
import com.github.h3yow.iotdevicetwinjava.model.ThresholdRule;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * 本地内存设备影子存储。
 *
 * 一致性策略：
 * - 每台设备一把锁（computeIfAbsent 惰性创建）。同一设备的上报、影子合并、
 *   规则评估、上下线、重连补发全部串行化，因此并发更新结论确定、可重放。
 * - 影子与注册信息以不可变快照 copy-on-write 保存，锁外读取不会看到半成品状态。
 * - 规则按 ruleId 排序，保证评估顺序确定。
 */
public class DeviceShadowStore {

    private final ConcurrentHashMap<String, DeviceShadow> shadows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DeviceRegistration> registrations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ThresholdRule> rules = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    /** 注册设备；重复注册按幂等处理（已存在且同型号时保留现有影子与状态）。 */
    public void register(DeviceRegistration registration) {
        registrations.put(registration.deviceId(), registration);
        shadows.computeIfAbsent(registration.deviceId(),
                id -> DeviceShadow.initialized(id, registration.deviceType()));
        locks.computeIfAbsent(registration.deviceId(), id -> new Object());
    }

    public Optional<DeviceRegistration> registration(String deviceId) {
        return Optional.ofNullable(registrations.get(deviceId));
    }

    public Optional<DeviceShadow> find(String deviceId) {
        return Optional.ofNullable(shadows.get(deviceId));
    }

    public DeviceShadow getOrThrow(String deviceId) {
        DeviceShadow shadow = shadows.get(deviceId);
        if (shadow == null) {
            throw new NoSuchElementException("设备未注册: " + deviceId);
        }
        return shadow;
    }

    /** 在设备锁内执行读改写，返回回调结果；设备不存在时抛异常。 */
    public <T> T mutateWithLock(String deviceId, Function<DeviceShadow, T> action) {
        Object lock = locks.computeIfAbsent(deviceId, id -> new Object());
        synchronized (lock) {
            return action.apply(getOrThrow(deviceId));
        }
    }

    /**
     * 事务：在设备锁内执行，回调通过 sink 落盘新版本影子（可落盘 0..1 次），
     * 保证“计算 + 落盘 + 返回结论”原子完成，杜绝两次取锁之间的交错。
     */
    public <T> T transact(String deviceId,
                          java.util.function.BiFunction<DeviceShadow, Sink, T> action) {
        Object lock = locks.computeIfAbsent(deviceId, id -> new Object());
        synchronized (lock) {
            return action.apply(getOrThrow(deviceId), updated -> {
                shadows.put(deviceId, updated);
                return updated;
            });
        }
    }

    /** 影子落盘回调。 */
    @FunctionalInterface
    public interface Sink {
        DeviceShadow save(DeviceShadow updated);
    }

    /** 在设备锁内执行纯函数式转换并落盘新版本影子，返回新快照。 */
    public DeviceShadow updateWithLock(String deviceId, UnaryOperator<DeviceShadow> transform) {
        Object lock = locks.computeIfAbsent(deviceId, id -> new Object());
        synchronized (lock) {
            DeviceShadow updated = transform.apply(getOrThrow(deviceId));
            shadows.put(deviceId, updated);
            return updated;
        }
    }

    public void addRule(ThresholdRule rule) {
        rules.put(rule.ruleId(), rule);
    }

    /** 取适用于该设备的规则，按 ruleId 排序以保证评估顺序确定。 */
    public List<ThresholdRule> rulesFor(String deviceId) {
        return rules.values().stream()
                .filter(r -> r.appliesTo(deviceId))
                .sorted(Comparator.comparing(ThresholdRule::ruleId))
                .toList();
    }

    public List<DeviceShadow> allShadows() {
        return List.copyOf(shadows.values());
    }
}
