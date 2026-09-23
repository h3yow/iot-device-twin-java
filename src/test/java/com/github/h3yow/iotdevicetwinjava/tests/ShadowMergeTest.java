package com.github.h3yow.iotdevicetwinjava.tests;

import com.github.h3yow.iotdevicetwinjava.model.RejectReason;
import com.github.h3yow.iotdevicetwinjava.model.ShadowPatch;
import com.github.h3yow.iotdevicetwinjava.model.UpdateOutcome;
import com.github.h3yow.iotdevicetwinjava.service.FieldSchemaRegistry;
import com.github.h3yow.iotdevicetwinjava.service.ShadowUpdateService;
import com.github.h3yow.iotdevicetwinjava.service.TelemetryService;
import com.github.h3yow.iotdevicetwinjava.model.TelemetryReport;
import com.github.h3yow.iotdevicetwinjava.service.RuleEngine;
import com.github.h3yow.iotdevicetwinjava.store.*;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 影子版本化合并：未知字段、落后版本、类型不匹配、过期补丁必须按可区分原因拒绝；
 * 同一批更新重放结论一致。
 */
class ShadowMergeTest {

    private static final Logger log = LoggerFactory.getLogger(ShadowMergeTest.class);

    private final DeviceStore deviceStore = new DeviceStore();
    private final IdempotencyStore idempotencyStore = new IdempotencyStore();
    private final FieldSchemaRegistry schema = new FieldSchemaRegistry();
    private final ShadowUpdateService shadowService = new ShadowUpdateService(
            deviceStore, idempotencyStore, schema);

    @Test
    void rejectsUnknownFieldStaleVersionTypeMismatchStaleTimestamp() {
        String device = "dev-s";

        UpdateOutcome unknown = shadowService.applyPatch(new ShadowPatch(device, 0, 100,
                Map.of("unknownField", 1), null));
        log.info("[TEST] 输入: baseVersion=0 timestamp=100 fields=unknownField; 触发结果 reason={} detail={}",
                unknown.reason(), unknown.detail());
        assertFalse(unknown.accepted());
        assertEquals(RejectReason.UNKNOWN_FIELD, unknown.reason());

        UpdateOutcome mismatch = shadowService.applyPatch(new ShadowPatch(device, 0, 100,
                Map.of("temperature", "hot"), null));
        log.info("[TEST] 输入: baseVersion=0 timestamp=100 temperature=\"hot\"; 触发结果 reason={} detail={}",
                mismatch.reason(), mismatch.detail());
        assertFalse(mismatch.accepted());
        assertEquals(RejectReason.TYPE_MISMATCH, mismatch.reason());

        UpdateOutcome ok = shadowService.applyPatch(new ShadowPatch(device, 0, 100,
                Map.of("temperature", 30.0), null));
        assertTrue(ok.applied());
        assertEquals(1, ok.version());
        log.info("[TEST] 合法补丁合并成功，版本递增到 {}", ok.version());

        UpdateOutcome staleVersion = shadowService.applyPatch(new ShadowPatch(device, 0, 200,
                Map.of("humidity", 50.0), null));
        log.info("[TEST] 输入: baseVersion=0(当前=1) timestamp=200; 触发结果 reason={} detail={}",
                staleVersion.reason(), staleVersion.detail());
        assertFalse(staleVersion.accepted());
        assertEquals(RejectReason.STALE_VERSION, staleVersion.reason());
        assertEquals(1, deviceStore.get(device).getVersion(), "拒绝不得改变版本");

        // 构造较新活动时间，再投递过期补丁
        new TelemetryService(deviceStore, idempotencyStore, schema,
                new RuleEngine(new RuleDefinitionStore(), new RuleStateStore(), new CommandStore()))
                .report(new TelemetryReport(device, 500, Map.of("temperature", 31.0), null));
        UpdateOutcome staleTs = shadowService.applyPatch(new ShadowPatch(device, 1, 400,
                Map.of("humidity", 60.0), null));
        log.info("[TEST] 输入: baseVersion=1 timestamp=400 < 最近活动500; 触发结果 reason={} detail={}",
                staleTs.reason(), staleTs.detail());
        assertFalse(staleTs.accepted());
        assertEquals(RejectReason.STALE_TIMESTAMP, staleTs.reason());
        assertEquals(1, deviceStore.get(device).getVersion());
    }

    @Test
    void successfulMergeBumpsVersionAndReplayIsIdentical() {
        String device = "dev-s2";
        var p1 = new ShadowPatch(device, 0, 100, Map.of("temperature", 20.0, "powerOn", true), "tok-1");
        var p1Replay = new ShadowPatch(device, 0, 100, Map.of("temperature", 20.0, "powerOn", true), "tok-1");

        UpdateOutcome first = shadowService.applyPatch(p1);
        UpdateOutcome replay = shadowService.applyPatch(p1Replay);
        log.info("[TEST] 同一批补丁(timestamp=100 token=tok-1)重放: first.version={} replay.version={} reason={}",
                first.version(), replay.version(), replay.reason());
        assertEquals(first, replay, "同一批更新重放后结论必须一致");
        assertEquals(1, deviceStore.get(device).getVersion(), "重放不得重复递增版本");
        assertEquals(20.0, deviceStore.get(device).getDesired().get("temperature"));
        assertEquals(true, deviceStore.get(device).getDesired().get("powerOn"));

        // 基于新版本继续合并，desired 为字段级合并而非覆盖
        UpdateOutcome second = shadowService.applyPatch(new ShadowPatch(device, 1, 200,
                Map.of("humidity", 70.0), null));
        assertTrue(second.applied());
        assertEquals(2, second.version());
        assertEquals(3, deviceStore.get(device).getDesired().size(), "desired 应保留旧字段并合入新字段");
        log.info("[TEST] 第二次合并后 desired={} version={}",
                deviceStore.get(device).getDesired(), deviceStore.get(device).getVersion());
    }
}
