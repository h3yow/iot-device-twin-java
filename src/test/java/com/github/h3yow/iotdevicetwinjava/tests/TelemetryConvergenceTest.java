package com.github.h3yow.iotdevicetwinjava.tests;

import com.github.h3yow.iotdevicetwinjava.model.RejectReason;
import com.github.h3yow.iotdevicetwinjava.model.TelemetryReport;
import com.github.h3yow.iotdevicetwinjava.model.UpdateOutcome;
import com.github.h3yow.iotdevicetwinjava.service.FieldSchemaRegistry;
import com.github.h3yow.iotdevicetwinjava.service.RuleEngine;
import com.github.h3yow.iotdevicetwinjava.service.TelemetryService;
import com.github.h3yow.iotdevicetwinjava.store.*;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 乱序、重复与过期遥测上报必须收敛到同一最终状态：
 * 每个字段取“最大时间戳对应值”，与到达顺序无关；重复上报不重复生效。
 */
class TelemetryConvergenceTest {

    private static final Logger log = LoggerFactory.getLogger(TelemetryConvergenceTest.class);

    private final DeviceStore deviceStore = new DeviceStore();
    private final IdempotencyStore idempotencyStore = new IdempotencyStore();
    private final CommandStore commandStore = new CommandStore();
    private final TelemetryService telemetryService = new TelemetryService(
            deviceStore, idempotencyStore, new FieldSchemaRegistry(),
            new RuleEngine(new RuleDefinitionStore(), new RuleStateStore(), commandStore));

    @Test
    void outOfOrderAndDuplicateReportsConverge() {
        String device = "dev-1";

        UpdateOutcome o1 = telemetryService.report(new TelemetryReport(device, 1000,
                Map.of("temperature", 40.0), null));
        UpdateOutcome stale = telemetryService.report(new TelemetryReport(device, 500,
                Map.of("temperature", 35.0), null));
        UpdateOutcome dup = telemetryService.report(new TelemetryReport(device, 1000,
                Map.of("temperature", 40.0), null));

        log.info("[TEST] 输入时间戳序列: 1000(40) -> 500(35,过期) -> 1000(40,重复)");
        log.info("[TEST] 判定依据: LWW 字段级比较；结果 o1.applied={} stale.applied={} reason={} dup.applied={}",
                o1.applied(), stale.applied(), stale.reason(), dup.applied());

        assertTrue(o1.applied());
        assertFalse(stale.applied(), "过期时间戳不得覆盖较新状态");
        assertEquals(RejectReason.NONE, stale.reason());
        assertFalse(dup.applied(), "重复上报不得重复累计");

        // 乱序：先到 ts=3000，再到更早 ts=2500
        telemetryService.report(new TelemetryReport(device, 3000, Map.of("temperature", 25.0), null));
        telemetryService.report(new TelemetryReport(device, 2500, Map.of("temperature", 22.0), null));

        var shadow = deviceStore.get(device);
        log.info("[TEST] 触发结果/最终状态: temperature={} ts={}",
                shadow.getReportedValue("temperature"), shadow.getReportedTimestamp("temperature"));
        assertEquals(25.0, shadow.getReportedValue("temperature"));
        assertEquals(3000L, shadow.getReportedTimestamp("temperature"),
                "乱序到达后状态必须收敛为最大时间戳的值");
    }

    @Test
    void sameClientTokenReplaysFirstOutcome() {
        String device = "dev-2";
        var r1 = new TelemetryReport(device, 1000, Map.of("humidity", 80.0), "token-A");
        var r2 = new TelemetryReport(device, 1000, Map.of("humidity", 80.0), "token-A");

        UpdateOutcome first = telemetryService.report(r1);
        UpdateOutcome replay = telemetryService.report(r2);

        log.info("[TEST] 相同 clientToken=token-A 重放，输入时间戳均为 1000");
        log.info("[TEST] 判定依据: 幂等存储命中首次结论；首次 applied={}, 重放 applied={}, reason={}",
                first.applied(), replay.applied(), replay.reason());
        assertEquals(first, replay, "同令牌重放结论必须完全一致");
        assertTrue(first.applied());
        // 重放走的是缓存短路，状态只来自首次写入；副作用不重复（规则命令去重见 RuleTriggerTest）
        assertEquals(80.0, deviceStore.get(device).getReportedValue("humidity"));
    }

    @Test
    void sameTimestampDifferentValueIsRejectedAsConflict() {
        String device = "dev-3";
        telemetryService.report(new TelemetryReport(device, 1000,
                Map.of("temperature", 40.0), null));
        UpdateOutcome conflict = telemetryService.report(new TelemetryReport(device, 1000,
                Map.of("temperature", 41.0), null));
        log.info("[TEST] 同时间戳 1000 两个不同值 40 vs 41，判定 reason={}, detail={}",
                conflict.reason(), conflict.detail());
        assertFalse(conflict.accepted());
        assertEquals(RejectReason.SAME_TIMESTAMP_CONFLICT, conflict.reason());
        assertEquals(40.0, deviceStore.get(device).getReportedValue("temperature"),
                "冲突数据不得覆盖原值");
    }
}
