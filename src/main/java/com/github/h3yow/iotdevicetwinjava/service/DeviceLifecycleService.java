package com.github.h3yow.iotdevicetwinjava.service;

import com.github.h3yow.iotdevicetwinjava.model.Command;
import com.github.h3yow.iotdevicetwinjava.model.DeviceSchema;
import com.github.h3yow.iotdevicetwinjava.model.DeviceShadow;
import com.github.h3yow.iotdevicetwinjava.model.DeviceStatus;
import com.github.h3yow.iotdevicetwinjava.model.ReconnectResult;
import com.github.h3yow.iotdevicetwinjava.store.DeviceShadowStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 设备生命周期服务。
 *
 * 上下线与补发语义：
 * - 上线/离线只改连接状态，不改变数据版本 version 与 reported/desired，
 *   因此“离线恢复后的补发不会造成状态回滚”。
 * - 离线期间规则触发的命令进入 pendingCommands（仍记录在影子中）。
 * - 重连 = markOnline + 按 triggerVersion 升序补发：每条 PENDING 命令补发一次后置为
 *   DELIVERED(deliveryMode=RECONNECT)，并登记 dedupKey；任何已在 deliveredDedupKeys
 *   中的键都跳过。补发动作不触碰任何字段状态、不增长数据版本。
 * - 注册幂等：重复注册同型号设备保留现有全部状态。
 */
public class DeviceLifecycleService {

    private static final Logger LOG = Logger.getLogger(DeviceLifecycleService.class.getName());

    private final DeviceShadowStore store;

    public DeviceLifecycleService(DeviceShadowStore store) {
        this.store = store;
    }

    /**
     * 注册设备。schema 为 null 时使用默认 schema（temperature NUMBER、humidity NUMBER、
     * mode STRING、enabled BOOLEAN），方便本地演示。
     */
    public DeviceShadow register(String deviceId, String deviceType) {
        DeviceSchema schema = store.registration(deviceId)
                .map(com.github.h3yow.iotdevicetwinjava.model.DeviceRegistration::schema)
                .orElseGet(() -> new DeviceSchema(
                        deviceType == null ? "default-thermostat" : deviceType, 1,
                        Map.of("temperature", com.github.h3yow.iotdevicetwinjava.model.FieldType.NUMBER,
                                "humidity", com.github.h3yow.iotdevicetwinjava.model.FieldType.NUMBER,
                                "mode", com.github.h3yow.iotdevicetwinjava.model.FieldType.STRING,
                                "enabled", com.github.h3yow.iotdevicetwinjava.model.FieldType.BOOLEAN)));
        store.register(new com.github.h3yow.iotdevicetwinjava.model.DeviceRegistration(
                deviceId, schema.deviceType(), schema));
        DeviceShadow shadow = store.getOrThrow(deviceId);
        LOG.info("[设备注册] device=" + deviceId + " type=" + schema.deviceType()
                + " schemaVersion=" + schema.schemaVersion() + " fields=" + schema.fields().keySet());
        return shadow;
    }

    public DeviceShadow markOnline(String deviceId, long timestamp) {
        DeviceShadow shadow = store.updateWithLock(deviceId, s -> {
            if (s.status() == DeviceStatus.ONLINE) {
                return s;
            }
            LOG.info("[设备上线] device=" + deviceId + " 输入时间戳=" + timestamp
                    + " 判定依据=OFFLINE->ONLINE 数据版本保持 v" + s.version());
            return copyStatus(s, DeviceStatus.ONLINE);
        });
        return shadow;
    }

    public DeviceShadow markOffline(String deviceId, long timestamp) {
        return store.updateWithLock(deviceId, s -> {
            if (s.status() == DeviceStatus.OFFLINE) {
                return s;
            }
            LOG.info("[设备离线] device=" + deviceId + " 输入时间戳=" + timestamp
                    + " 判定依据=ONLINE->OFFLINE，期间命令将积压 数据版本保持 v" + s.version());
            return copyStatus(s, DeviceStatus.OFFLINE);
        });
    }

    /** 重连：上线并补发积压命令；补发不改状态、不重复。 */
    public ReconnectResult reconnect(String deviceId, long timestamp) {
        return store.transact(deviceId, (s0, sink) -> {
            DeviceShadow s = s0.status() == DeviceStatus.OFFLINE ? copyStatus(s0, DeviceStatus.ONLINE) : s0;
            LOG.info("[设备重连] device=" + deviceId + " 输入时间戳=" + timestamp
                    + " 判定依据=重连恢复 数据版本保持 v" + s.version()
                    + " 待补发命令=" + s.pendingCommands().size());

            List<Command> pending = new ArrayList<>(s.pendingCommands());
            pending.sort(java.util.Comparator.comparingLong(Command::triggerVersion)
                    .thenComparing(Command::dedupKey));

            List<Command> redelivered = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            List<Command> delivered = new ArrayList<>(s.deliveredCommands());
            List<String> dedupKeys = new ArrayList<>(s.deliveredDedupKeys());

            for (Command command : pending) {
                if (dedupKeys.contains(command.dedupKey())) {
                    skipped.add(command.dedupKey());
                    LOG.info("[重连补发] device=" + deviceId + " 命令=" + command.commandId()
                            + " 判定依据=dedupKey 已下发(" + command.dedupKey()
                            + ") 触发结果=跳过，不重复执行");
                    continue;
                }
                Command redeliveredCmd = command.delivered(timestamp, "RECONNECT");
                redelivered.add(redeliveredCmd);
                delivered.add(redeliveredCmd);
                dedupKeys.add(command.dedupKey());
                LOG.info("[重连补发] device=" + deviceId + " 命令=" + command.commandId()
                        + " rule=" + command.ruleId() + " triggerVersion=" + command.triggerVersion()
                        + " 判定依据=离线积压且未下发过 触发结果=补发一次(RECONNECT)，状态不回滚");
            }

            DeviceShadow after = new DeviceShadow(s.deviceId(), s.deviceType(), s.version(),
                    s.reported(), s.desired(), DeviceStatus.ONLINE, s.lastSeenTs(),
                    List.of(), List.copyOf(delivered), List.copyOf(dedupKeys),
                    s.processedReportIds(), s.processedReportFingerprints(),
                    s.processedUpdates(), s.ruleStates());
            sink.save(after);

            ReconnectResult result = new ReconnectResult(deviceId, s.version(),
                    List.copyOf(redelivered), List.copyOf(skipped));
            LOG.info("[重连结论] device=" + deviceId + " 补发=" + redelivered.size()
                    + " 跳过重复=" + skipped.size() + " 数据版本保持 v" + s.version());
            return result;
        });
    }

    private DeviceShadow copyStatus(DeviceShadow s, DeviceStatus status) {
        return new DeviceShadow(s.deviceId(), s.deviceType(), s.version(),
                s.reported(), s.desired(), status, s.lastSeenTs(),
                s.pendingCommands(), s.deliveredCommands(), s.deliveredDedupKeys(),
                s.processedReportIds(), s.processedReportFingerprints(),
                s.processedUpdates(), s.ruleStates());
    }
}
