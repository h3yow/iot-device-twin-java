package com.github.h3yow.iotdevicetwinjava.store;

import com.github.h3yow.iotdevicetwinjava.model.DeviceShadow;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 本地内存设备影子存储。
 * 每个设备一把锁，所有对该设备影子的读改写必须持有该锁，保证并发更新串行化、可确定。
 */
@Component
public class DeviceStore {

    private final ConcurrentHashMap<String, DeviceShadow> shadows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public DeviceShadow getOrCreate(String deviceId) {
        return shadows.computeIfAbsent(deviceId, DeviceShadow::new);
    }

    public DeviceShadow get(String deviceId) {
        return shadows.get(deviceId);
    }

    /** 获取设备级互斥锁（实现阶段由各服务在同一锁内完成读改写与规则判定）。 */
    public ReentrantLock lockFor(String deviceId) {
        return locks.computeIfAbsent(deviceId, k -> new ReentrantLock());
    }
}
