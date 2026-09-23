package com.github.h3yow.iotdevicetwinjava.service;

import com.github.h3yow.iotdevicetwinjava.model.Command;
import com.github.h3yow.iotdevicetwinjava.model.DeviceStatus;
import com.github.h3yow.iotdevicetwinjava.store.CommandStore;
import com.github.h3yow.iotdevicetwinjava.store.DeviceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 设备连接生命周期处理。
 *
 * - {@link #markOffline}：仅切换状态，不清除影子（状态不回滚）。
 * - {@link #reconnect} / {@link #markOnline}：置在线后，将离线期间排队的命令
 *   按 createdAt 升序逐条补发；每条命令只标记送达一次，重复重连不会重复执行。
 *   补发全程不修改 reported/desired/版本号。
 */
@Service
public class DeviceConnectionService {

    private static final Logger log = LoggerFactory.getLogger(DeviceConnectionService.class);

    private final DeviceStore deviceStore;
    private final CommandStore commandStore;

    public DeviceConnectionService(DeviceStore deviceStore, CommandStore commandStore) {
        this.deviceStore = deviceStore;
        this.commandStore = commandStore;
    }

    public void markOnline(String deviceId) {
        reconnect(deviceId);
    }

    public void markOffline(String deviceId) {
        ReentrantLock lock = deviceStore.lockFor(deviceId);
        lock.lock();
        try {
            var shadow = deviceStore.getOrCreate(deviceId);
            shadow.setStatus(DeviceStatus.OFFLINE);
            log.info("[connect] device={} 标记离线；影子保持不变 version={} reported={}",
                    deviceId, shadow.getVersion(), shadow.getReported());
        } finally {
            lock.unlock();
        }
    }

    /** 重连：置在线并补发离线期间排队命令（每条只送达一次）。返回本次实际补发的命令。 */
    public List<Command> reconnect(String deviceId) {
        ReentrantLock lock = deviceStore.lockFor(deviceId);
        lock.lock();
        try {
            var shadow = deviceStore.getOrCreate(deviceId);
            shadow.setStatus(DeviceStatus.ONLINE);

            List<Command> pending = commandStore.pending(deviceId);
            log.info("[connect] device={} 重连/上线，待补发命令 {} 条", deviceId, pending.size());
            for (Command command : pending) {
                // markDelivered 为 CAS：并发/重复重连下只有一次成功，绝不重复执行
                boolean delivered = commandStore.markDelivered(command.commandId());
                log.info("[connect] device={} 补发 commandId={} rule={} name={} 送达={}",
                        deviceId, command.commandId(), command.ruleId(), command.name(), delivered);
            }
            // 返回标记后的最新命令视图（delivered=true），且已送达命令不再出现在 pending 中
            List<Command> redelivered = pending.stream()
                    .map(c -> commandStore.get(c.commandId()))
                    .toList();
            log.info("[connect] device={} 补发完成；影子未回滚 version={} reported={}",
                    deviceId, shadow.getVersion(), shadow.getReported());
            return redelivered;
        } finally {
            lock.unlock();
        }
    }
}
