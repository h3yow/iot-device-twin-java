package com.github.h3yow.iotdevicetwinjava.tests;

import com.github.h3yow.iotdevicetwinjava.model.Command;
import com.github.h3yow.iotdevicetwinjava.model.DeviceStatus;
import com.github.h3yow.iotdevicetwinjava.model.RuleDefinition;
import com.github.h3yow.iotdevicetwinjava.model.RuleOperator;
import com.github.h3yow.iotdevicetwinjava.model.TelemetryReport;
import com.github.h3yow.iotdevicetwinjava.service.DeviceConnectionService;
import com.github.h3yow.iotdevicetwinjava.service.FieldSchemaRegistry;
import com.github.h3yow.iotdevicetwinjava.service.RuleEngine;
import com.github.h3yow.iotdevicetwinjava.service.TelemetryService;
import com.github.h3yow.iotdevicetwinjava.store.*;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 设备离线期间规则触发的命令进入补发队列；
 * 重连后按序补发且每条只送达一次；重复重连不重复执行、影子不回滚。
 */
class ReconnectionTest {

    private static final Logger log = LoggerFactory.getLogger(ReconnectionTest.class);

    private final DeviceStore deviceStore = new DeviceStore();
    private final IdempotencyStore idempotencyStore = new IdempotencyStore();
    private final CommandStore commandStore = new CommandStore();
    private final RuleStateStore ruleStateStore = new RuleStateStore();
    private final RuleDefinitionStore ruleDefinitionStore = new RuleDefinitionStore();
    private final RuleEngine ruleEngine = new RuleEngine(ruleDefinitionStore, ruleStateStore, commandStore);
    private final TelemetryService telemetryService = new TelemetryService(
            deviceStore, idempotencyStore, new FieldSchemaRegistry(), ruleEngine);
    private final DeviceConnectionService connectionService =
            new DeviceConnectionService(deviceStore, commandStore);

    @Test
    void offlineCommandsQueueAndReconnectRedeliversOnceWithoutRollback() {
        ruleDefinitionStore.register(new RuleDefinition("overheat", "temperature",
                RuleOperator.GT, 30.0, "cool-down", Map.of("fan", "on")));
        String device = "dev-off";

        // 初始在线，上报一次正常数据
        deviceStore.getOrCreate(device).setStatus(DeviceStatus.ONLINE);
        telemetryService.report(new TelemetryReport(device, 100, Map.of("temperature", 20.0), null));

        // 断连
        connectionService.markOffline(device);
        assertEquals(DeviceStatus.OFFLINE, deviceStore.get(device).getStatus());

        // 离线期间乱序到达两条超标遥测（同一持续条件，只触发一次）
        telemetryService.report(new TelemetryReport(device, 300, Map.of("temperature", 35.0), null));
        telemetryService.report(new TelemetryReport(device, 200, Map.of("temperature", 33.0), null));

        List<Command> pending = commandStore.pending(device);
        log.info("[TEST] 离线期间输入 ts=300(35),ts=200(33) 乱序；判定依据: 过期跳过+armed去重；待发命令={}",
                pending.stream().map(c -> c.commandId() + ":delivered=" + c.delivered()).toList());
        assertEquals(1, pending.size(), "离线期间同一持续条件只排队一条命令");
        assertFalse(pending.get(0).delivered());
        assertEquals(35.0, deviceStore.get(device).getReportedValue("temperature"),
                "离线期间的较新上报必须被影子收敛，不丢失");
        int versionBefore = deviceStore.get(device).getVersion();

        // 重连：补发
        List<Command> redelivered = connectionService.reconnect(device);
        log.info("[TEST] 重连补发结果: {}；设备状态={}",
                redelivered.stream().map(c -> c.commandId() + ":delivered=" + c.delivered()).toList(),
                deviceStore.get(device).getStatus());
        assertEquals(1, redelivered.size());
        assertTrue(redelivered.get(0).delivered());
        assertEquals(DeviceStatus.ONLINE, deviceStore.get(device).getStatus());

        // 影子不得回滚
        assertEquals(35.0, deviceStore.get(device).getReportedValue("temperature"));
        assertEquals(versionBefore, deviceStore.get(device).getVersion(), "补发不得改动版本");

        // 再次重连：无新的待发命令，且命令不会被重复执行
        List<Command> second = connectionService.reconnect(device);
        log.info("[TEST] 重复重连补发结果: {} (应为空)", second);
        assertTrue(second.isEmpty(), "重复重连不得重复执行已送达命令");
        assertEquals(1, commandStore.forDevice(device).size(), "命令记录保持唯一");
    }

    @Test
    void staleTelemetryWhileOfflineNeverRollsBackState() {
        String device = "dev-off2";
        deviceStore.getOrCreate(device).setStatus(DeviceStatus.ONLINE);
        telemetryService.report(new TelemetryReport(device, 1000, Map.of("temperature", 28.0), null));
        connectionService.markOffline(device);
        // 迟到的旧数据
        var stale = telemetryService.report(new TelemetryReport(device, 900, Map.of("temperature", 5.0), null));
        log.info("[TEST] 离线期间收到过期上报 ts=900(5) 而当前为 ts=1000(28)；判定依据: applied={}, reason={}",
                stale.applied(), stale.reason());
        assertFalse(stale.applied());
        assertEquals(28.0, deviceStore.get(device).getReportedValue("temperature"),
                "过期数据在断连场景下同样不得回滚状态");
    }
}
