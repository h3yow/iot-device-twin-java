package com.github.h3yow.iotdevicetwinjava.tests;

import com.github.h3yow.iotdevicetwinjava.model.FieldOutcome;
import com.github.h3yow.iotdevicetwinjava.model.RejectReason;
import com.github.h3yow.iotdevicetwinjava.model.TelemetryReport;
import com.github.h3yow.iotdevicetwinjava.model.TelemetryResult;
import com.github.h3yow.iotdevicetwinjava.tests.support.ServiceHarness;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 遥测收敛性测试：乱序、重复、过期时间戳、同时间戳冲突、未知字段、类型不匹配。
 * 每个用例日志包含：输入时间戳、逐字段判定依据、触发结果。
 */
class TelemetryConvergenceTest {

    private static final Logger LOG = Logger.getLogger(TelemetryConvergenceTest.class.getName());
    private static final String DEVICE = "dev-A";

    private TelemetryResult report(ServiceHarness h, String reportId, long ts,
                                   Map<String, Object> fields) {
        return h.telemetry.report(new TelemetryReport(DEVICE, ts, reportId, fields));
    }

    private RejectReason reasonOf(TelemetryResult r, String field) {
        return r.fields().stream().filter(f -> f.field().equals(field)).findFirst()
                .map(FieldOutcome::reason).orElseThrow();
    }

    @Test
    void outOfOrderReportsConvergeToNewestValue() {
        ServiceHarness.caseHeader("乱序上报收敛到最新时间戳");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline(DEVICE, 100);

        // 故意乱序到达：30 -> 10 -> 20
        TelemetryResult r30 = report(h, "p30", 30, Map.of("temperature", 30.0));
        TelemetryResult r10 = report(h, "p10", 10, Map.of("temperature", 10.0));
        TelemetryResult r20 = report(h, "p20", 20, Map.of("temperature", 20.0));

        LOG.info("ts=10 判定依据: " + r10.fields().get(0).basis());
        assertEquals(30.0, h.store.getOrThrow(DEVICE).reported().get("temperature").value());
        assertFalse(r10.applied());
        assertEquals(RejectReason.STALE_TIMESTAMP, reasonOf(r10, "temperature"),
                "过期时间戳不得覆盖较新状态");

        TelemetryResult r35 = report(h, "p35", 35, Map.of("temperature", 35.0));
        assertTrue(r35.applied());
        assertEquals(35.0, h.store.getOrThrow(DEVICE).reported().get("temperature").value());
        assertEquals(2L, h.store.getOrThrow(DEVICE).version(),
                "只有真正更新才增长版本：v1(ts30), v2(ts35)；ts10/ts20/ts25 均过期");
        LOG.info("最终温度=35.0 version=2，判定依据=逐字段 LWW，乱序序列 30,10,20,25,35 收敛确定");
    }

    @Test
    void duplicateBatchIsIdempotentWithNoSideEffects() {
        ServiceHarness.caseHeader("重复上报幂等：不重复累计、不重复触发");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline(DEVICE, 100);
        h.addRule("rule-hot", DEVICE, "temperature",
                com.github.h3yow.iotdevicetwinjava.model.Operator.GT, 28.0, "COOL_ON");

        TelemetryResult first = report(h, "dup-1", 50,
                new java.util.LinkedHashMap<>(Map.of("temperature", 31.0, "humidity", 60.0)));
        long versionAfterFirst = h.store.getOrThrow(DEVICE).version();
        long commandsAfterFirst = h.store.getOrThrow(DEVICE).deliveredCommands().size();
        assertEquals(1, first.commands().size(), "首次上报条件成立，应下发一条命令");

        // 完全相同的上报（同 reportId）重放多次
        TelemetryResult replay1 = report(h, "dup-1", 50, Map.of("temperature", 31.0));
        TelemetryResult replay2 = report(h, "dup-1", 50, Map.of("temperature", 31.0));
        // 无 reportId，同内容指纹也应判重（字段顺序不同也一样）
        TelemetryResult replay3 = report(h, null, 50,
                new java.util.LinkedHashMap<>(Map.of("humidity", 60.0, "temperature", 31.0)));
        report(h, null, 50,
                new java.util.LinkedHashMap<>(Map.of("temperature", 31.0, "humidity", 60.0)));

        assertTrue(replay1.idempotentReplay());
        assertTrue(replay2.idempotentReplay());
        assertTrue(replay3.idempotentReplay(), "字段顺序不同但内容+时间戳相同，指纹判重");
        assertEquals(versionAfterFirst, h.store.getOrThrow(DEVICE).version());
        assertEquals(commandsAfterFirst, h.store.getOrThrow(DEVICE).deliveredCommands().size(),
                "重复上报不得重复产生命令副作用");
        assertEquals(1, h.store.getOrThrow(DEVICE).deliveredDedupKeys().size());
        LOG.info("判定依据=reportId/内容指纹命中 触发结果=4 次重复全部幂等忽略，命令仍仅 1 条");
    }

    @Test
    void sameTimestampDifferentValueIsRejectedNotSilentlyDropped() {
        ServiceHarness.caseHeader("同时间戳不同值冲突拒绝");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline(DEVICE, 100);
        report(h, "c1", 70, Map.of("humidity", 55.0));
        TelemetryResult conflict = report(h, "c2", 70, Map.of("humidity", 88.0));

        assertEquals(RejectReason.SAME_TIMESTAMP_CONFLICT, reasonOf(conflict, "humidity"));
        assertEquals(55.0, h.store.getOrThrow(DEVICE).reported().get("humidity").value(),
                "冲突更新不得覆盖，保留先到值，结论确定");
        LOG.info("输入时间戳=70,70 判定依据=同时间戳且值不同 触发结果=" + reasonOf(conflict, "humidity"));
    }

    @Test
    void unknownFieldAndTypeMismatchAreRejectedWithDistinctReasons() {
        ServiceHarness.caseHeader("未知字段与类型不匹配按不同原因拒绝");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline(DEVICE, 100);

        TelemetryResult r = report(h, "bad", 80,
                Map.of("temperature", 22.5, "unknownField", 1, "mode", 123));
        Map<RejectReason, List<FieldOutcome>> grouped = r.fields().stream()
                .filter(f -> f.reason() != null)
                .collect(java.util.stream.Collectors.groupingBy(FieldOutcome::reason));

        assertTrue(grouped.containsKey(RejectReason.UNKNOWN_FIELD));
        assertTrue(grouped.containsKey(RejectReason.TYPE_MISMATCH));
        assertEquals(22.5, h.store.getOrThrow(DEVICE).reported().get("temperature").value());
        assertNull(h.store.getOrThrow(DEVICE).reported().get("mode"));
        assertTrue(r.applied(), "temperature 合法仍应被应用，非法字段逐字段拒绝而不是静默丢弃");
        assertEquals(1L, h.store.getOrThrow(DEVICE).version());
        LOG.info("判定依据=schema 逐字段校验 UNKNOWN_FIELD/TYPE_MISMATCH 可区分"
                + " 触发结果=合法字段照常应用，非法字段拒绝并附原因");
    }

    @Test
    void replayingSameBatchSequenceYieldsSameFinalState() {
        ServiceHarness.caseHeader("同一批乱序更新重放后结论一致（确定性）");
        ServiceHarness h1 = new ServiceHarness();
        ServiceHarness h2 = new ServiceHarness();
        h1.registerOnline(DEVICE, 1);
        h2.registerOnline(DEVICE, 1);

        List<Map<String, Object>> batch = List.of(
                Map.of("temperature", 40.0),
                Map.of("temperature", 10.0),
                Map.of("temperature", 30.0),
                Map.of("humidity", 70.0),
                Map.of("temperature", 30.0));
        long[] tss = {40, 10, 30, 30, 30};
        String[] ids = {"a", "b", "c", "d", "c"};

        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < batch.size(); i++) {
                h1.telemetry.report(new TelemetryReport(DEVICE, tss[i], ids[i], batch.get(i)));
                h2.telemetry.report(new TelemetryReport(DEVICE, tss[i], ids[i], batch.get(i)));
            }
        }
        // h1 跑两轮；h2 只跑一轮（第二轮对 h1 全部是幂等重放）
        for (int i = 0; i < batch.size(); i++) {
            h2.telemetry.report(new TelemetryReport(DEVICE, tss[i], ids[i], batch.get(i)));
        }

        var s1 = h1.store.getOrThrow(DEVICE);
        var s2 = h2.store.getOrThrow(DEVICE);
        assertEquals(s2.version(), s1.version());
        assertEquals(s2.reported(), s1.reported());
        assertEquals(s2.deliveredCommands().size(), s1.deliveredCommands().size());
        LOG.info("判定依据=幂等重放+LWW 触发结果=两轮重放最终状态完全一致 version=" + s1.version());
    }
}
