package com.github.h3yow.iotdevicetwinjava.tests;

import com.github.h3yow.iotdevicetwinjava.model.Command;
import com.github.h3yow.iotdevicetwinjava.model.CommandState;
import com.github.h3yow.iotdevicetwinjava.model.DeviceShadow;
import com.github.h3yow.iotdevicetwinjava.model.ReconnectResult;
import com.github.h3yow.iotdevicetwinjava.model.TelemetryReport;
import com.github.h3yow.iotdevicetwinjava.tests.support.ServiceHarness;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 断连重连测试：
 * - 在线时触发立即下发；离线时触发进入积压；
 * - 重连按触发版本升序补发，每条恰好一次；重复重连不重复补发；
 * - 补发不改变数据版本与字段状态（无回滚）；
 * - 离线期间的过期/重复遥测语义与在线时一致。
 */
class OfflineReconnectTest {

    private static final Logger LOG = Logger.getLogger(OfflineReconnectTest.class.getName());
    private static final String DEVICE = "dev-E";

    @Test
    void offlineCommandsAreQueuedAndRedeliveredOnceOnReconnect() {
        ServiceHarness.caseHeader("离线积压 -> 重连升序补发一次，状态不回滚");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline(DEVICE, 1);
        h.addRule("r-hot", DEVICE, "temperature",
                com.github.h3yow.iotdevicetwinjava.model.Operator.GTE, 30.0, "COOL_ON");
        h.addRule("r-cold", DEVICE, "temperature",
                com.github.h3yow.iotdevicetwinjava.model.Operator.LTE, 10.0, "HEAT_ON");

        // 在线期间先触发 r-hot（立即下发）
        h.telemetry.report(new TelemetryReport(DEVICE, 10, "on1", Map.of("temperature", 31.0)));
        // 温度回落解除 r-hot
        h.telemetry.report(new TelemetryReport(DEVICE, 11, "on2", Map.of("temperature", 20.0)));

        h.lifecycle.markOffline(DEVICE, 12);
        // 离线期间再次过热：命令积压
        h.telemetry.report(new TelemetryReport(DEVICE, 13, "off1", Map.of("temperature", 32.0)));
        // 离线期间过冷：先解除热条件再进入冷条件（不同版本的两条积压命令）
        h.telemetry.report(new TelemetryReport(DEVICE, 14, "off2", Map.of("temperature", 20.0)));
        h.telemetry.report(new TelemetryReport(DEVICE, 15, "off3", Map.of("temperature", 5.0)));

        DeviceShadow offlineShadow = h.store.getOrThrow(DEVICE);
        assertEquals(2, offlineShadow.pendingCommands().size(), "离线触发应积压 2 条");
        assertEquals(1, offlineShadow.deliveredCommands().size(), "在线那 1 条已立即下发");
        long versionBeforeReconnect = offlineShadow.version();
        LOG.info("输入时间戳=13/15 离线触发 判定依据=OFFLINE 状态命令入 PENDING"
                + " 触发结果=积压 2 条，数据版本 v" + versionBeforeReconnect);

        ReconnectResult result = h.lifecycle.reconnect(DEVICE, 100);

        assertEquals(2, result.redeliveredCommands().size(), "积压命令补发 2 条");
        assertEquals(List.of("r-hot", "r-cold"),
                result.redeliveredCommands().stream().map(Command::ruleId).toList(),
                "按触发版本升序补发：先 r-hot(v3) 后 r-cold(v5)");
        assertTrue(result.redeliveredCommands().stream()
                .allMatch(c -> c.state() == CommandState.DELIVERED && "RECONNECT".equals(c.deliveryMode())));

        DeviceShadow after = h.store.getOrThrow(DEVICE);
        assertEquals(versionBeforeReconnect, after.version(), "补发不得改变数据版本（无回滚/无新增版本）");
        assertEquals(5.0, after.reported().get("temperature").value(),
                "字段状态为最后有效上报 5.0，补发不得回滚为旧值");
        assertEquals(0, after.pendingCommands().size());

        // 再次重连：没有新积压，且绝不重复补发
        ReconnectResult second = h.lifecycle.reconnect(DEVICE, 101);
        assertEquals(0, second.redeliveredCommands().size());
        assertEquals(3, after.deliveredCommands().size());
        LOG.info("输入时间戳=100/101 两次重连 判定依据=补发后 pending 清空且 dedupKey 已登记"
                + " 触发结果=首次补发 2 条，第二次 0 条，版本保持 v" + after.version());
    }

    @Test
    void reconnectDoesNotRollbackStateAndStaleOfflineDataRejected() {
        ServiceHarness.caseHeader("补发不回滚状态；离线期过期/重复遥测仍收敛");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline(DEVICE, 1);
        h.lifecycle.markOffline(DEVICE, 50);

        h.telemetry.report(new TelemetryReport(DEVICE, 60, "a", Map.of("temperature", 25.0)));
        // 乱序过期数据
        var stale = h.telemetry.report(new TelemetryReport(DEVICE, 55, "b", Map.of("temperature", 9.0)));
        assertFalse(stale.applied());
        // 重放重复
        var dup = h.telemetry.report(new TelemetryReport(DEVICE, 60, "a", Map.of("temperature", 25.0)));
        assertTrue(dup.idempotentReplay());

        ReconnectResult r = h.lifecycle.reconnect(DEVICE, 90);
        DeviceShadow after = h.store.getOrThrow(DEVICE);
        assertEquals(25.0, after.reported().get("temperature").value(),
                "过期的 9.0 不得在重连后覆盖 25.0，状态不回滚");
        assertEquals(0, r.redeliveredCommands().size(), "25.0 不命中任何阈值规则");
        LOG.info("输入时间戳=55(过期)/60/重放 判定依据=LWW 与幂等在离线态同样生效"
                + " 触发结果=重连后温度保持 25.0，无命令");
    }

    @Test
    void commandProducedOfflineThenReplayedTelemetryDoesNotDuplicate() {
        ServiceHarness.caseHeader("离线积压后重放触发上报，不产生重复命令");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline(DEVICE, 1);
        h.addRule("r-hot", DEVICE, "temperature",
                com.github.h3yow.iotdevicetwinjava.model.Operator.GTE, 30.0, "COOL_ON");
        h.lifecycle.markOffline(DEVICE, 5);

        var first = h.telemetry.report(new TelemetryReport(DEVICE, 10, "x", Map.of("temperature", 40.0)));
        assertEquals(1, first.commands().size());
        var replay = h.telemetry.report(new TelemetryReport(DEVICE, 10, "x", Map.of("temperature", 40.0)));
        assertTrue(replay.idempotentReplay());

        ReconnectResult r = h.lifecycle.reconnect(DEVICE, 20);
        assertEquals(1, r.redeliveredCommands().size());
        assertEquals(1, h.store.getOrThrow(DEVICE).deliveredDedupKeys().size());
        LOG.info("判定依据=整批幂等 + dedupKey 双重防护 触发结果=重放不新增积压，补发恰好 1 条");
    }
}
