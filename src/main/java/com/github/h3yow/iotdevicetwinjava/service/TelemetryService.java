package com.github.h3yow.iotdevicetwinjava.service;

import com.github.h3yow.iotdevicetwinjava.model.Command;
import com.github.h3yow.iotdevicetwinjava.model.DeviceShadow;
import com.github.h3yow.iotdevicetwinjava.model.FieldOutcome;
import com.github.h3yow.iotdevicetwinjava.model.FieldType;
import com.github.h3yow.iotdevicetwinjava.model.RejectReason;
import com.github.h3yow.iotdevicetwinjava.model.TaggedValue;
import com.github.h3yow.iotdevicetwinjava.model.TelemetryReport;
import com.github.h3yow.iotdevicetwinjava.model.TelemetryResult;
import com.github.h3yow.iotdevicetwinjava.model.TriggerResult;
import com.github.h3yow.iotdevicetwinjava.store.DeviceShadowStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * 遥测上报服务。
 *
 * 收敛与幂等规则（同一设备全程在设备锁内串行执行）：
 * 1. 整批幂等：优先按 reportId 判重；无 reportId 时按 (timestamp, 排序后字段) 的
 *    SHA-256 内容指纹判重。命中即返回首次处理结论，不再写状态、不再评估规则、
 *    不再产生命令（重复上报不重复累计/不重复副作用）。
 * 2. 按字段 last-writer-wins：incomingTs > currentTs 才覆盖；incomingTs < currentTs
 *    以 STALE_TIMESTAMP 拒绝（过期数据不覆盖较新状态）。
 * 3. 同时间戳：值相等视为重复字段（幂等忽略、无写入、无版本增长）；
 *    值不同以 SAME_TIMESTAMP_CONFLICT 拒绝（顺序无法确定，禁止随机选取）。
 * 4. schema 校验：未知字段 UNKNOWN_FIELD、类型不符 TYPE_MISMATCH，均逐字段给出原因。
 * 5. 至少一个字段被新应用才使数据版本 +1；随后驱动一次规则评估。
 */
public class TelemetryService {

    private static final Logger LOG = Logger.getLogger(TelemetryService.class.getName());

    private final DeviceShadowStore store;
    private final RuleEngine ruleEngine;

    public TelemetryService(DeviceShadowStore store, RuleEngine ruleEngine) {
        this.store = store;
        this.ruleEngine = ruleEngine;
    }

    public TelemetryResult report(TelemetryReport report) {
        LOG.info("[遥测上报] device=" + report.deviceId() + " reportId=" + report.reportId()
                + " 输入时间戳=" + report.timestamp() + " fields=" + new TreeMap<>(report.fields()));

        return store.transact(report.deviceId(), (shadow, sink) -> {
            var registration = store.registration(report.deviceId()).orElseThrow();
            String fingerprint = fingerprint(report);

            // ---- 1. 整批幂等判定 ----
            Long idempotentVersion = null;
            if (report.reportId() != null && !report.reportId().isBlank()) {
                idempotentVersion = shadow.processedReportIds().get(report.reportId());
            }
            if (idempotentVersion == null) {
                idempotentVersion = shadow.processedReportFingerprints().get(fingerprint);
            }
            if (idempotentVersion != null) {
                LOG.info("[遥测结论] device=" + report.deviceId()
                        + " 判定依据=整批重复(reportId/内容指纹命中首次处理版本 v" + idempotentVersion
                        + ") 触发结果=幂等忽略，无副作用 version=" + shadow.version());
                return new TelemetryResult(report.deviceId(), false, true, shadow.version(),
                        List.of(), List.of(), List.of());
            }

            // ---- 2. 逐字段判定 ----
            Map<String, TaggedValue> newReported = new LinkedHashMap<>(shadow.reported());
            List<FieldOutcome> outcomes = new ArrayList<>();
            boolean anyApplied = false;
            for (Map.Entry<String, Object> entry : new TreeMap<>(report.fields()).entrySet()) {
                String field = entry.getKey();
                Object value = entry.getValue();
                FieldType declared = registration.schema().fields().get(field);
                TaggedValue current = newReported.get(field);

                if (declared == null) {
                    outcomes.add(FieldOutcome.rejected(field, value, report.timestamp(),
                            current == null ? null : current.value(),
                            current == null ? null : current.timestamp(),
                            RejectReason.UNKNOWN_FIELD,
                            "字段 '" + field + "' 不在 schema v" + registration.schema().schemaVersion()
                                    + " 允许集合 " + registration.schema().fields().keySet()));
                    continue;
                }
                if (!declared.matches(value)) {
                    outcomes.add(FieldOutcome.rejected(field, value, report.timestamp(),
                            current == null ? null : current.value(),
                            current == null ? null : current.timestamp(),
                            RejectReason.TYPE_MISMATCH,
                            "字段 '" + field + "' 声明类型=" + declared + "，实际类型="
                                    + declared.describe(value)));
                    continue;
                }
                if (current != null) {
                    if (report.timestamp() < current.timestamp()) {
                        outcomes.add(FieldOutcome.rejected(field, value, report.timestamp(),
                                current.value(), current.timestamp(), RejectReason.STALE_TIMESTAMP,
                                "incomingTs=" + report.timestamp() + " < currentTs=" + current.timestamp()
                                        + "，过期数据不得覆盖较新状态"));
                        continue;
                    }
                    if (report.timestamp() == current.timestamp()) {
                        if (java.util.Objects.equals(value, current.value())) {
                            outcomes.add(FieldOutcome.rejected(field, value, report.timestamp(),
                                    current.value(), current.timestamp(), RejectReason.DUPLICATE_REPORT,
                                    "incomingTs=currentTs=" + report.timestamp() + " 且值相等，"
                                            + "重复字段幂等忽略，不重复累计"));
                            continue;
                        }
                        outcomes.add(FieldOutcome.rejected(field, value, report.timestamp(),
                                current.value(), current.timestamp(), RejectReason.SAME_TIMESTAMP_CONFLICT,
                                "incomingTs=currentTs=" + report.timestamp() + " 但值不同("
                                        + value + " vs " + current.value() + ")，先后无法确定，拒绝"));
                        continue;
                    }
                }

                Object previous = current == null ? null : current.value();
                Long previousTs = current == null ? null : current.timestamp();
                newReported.put(field, new TaggedValue(value, report.timestamp(), "telemetry"));
                anyApplied = true;
                outcomes.add(FieldOutcome.applied(field, value, report.timestamp(), previous, previousTs));
            }

            // ---- 3. 落盘（版本单调）+ 记录幂等键 ----
            long newVersion = shadow.version() + (anyApplied ? 1 : 0);
            Map<String, Long> byId = new LinkedHashMap<>(shadow.processedReportIds());
            Map<String, Long> byFp = new LinkedHashMap<>(shadow.processedReportFingerprints());
            if (report.reportId() != null && !report.reportId().isBlank()) {
                byId.put(report.reportId(), newVersion);
            }
            byFp.put(fingerprint, newVersion);

            DeviceShadow written = new DeviceShadow(shadow.deviceId(), shadow.deviceType(), newVersion,
                    Map.copyOf(newReported), shadow.desired(), shadow.status(),
                    anyApplied ? report.timestamp() : shadow.lastSeenTs(),
                    shadow.pendingCommands(), shadow.deliveredCommands(), shadow.deliveredDedupKeys(),
                    Map.copyOf(byId), Map.copyOf(byFp), shadow.processedUpdates(), shadow.ruleStates());

            // ---- 4. 仅在状态发生变化时驱动规则评估，结果与新影子一次性原子落盘 ----
            List<TriggerResult> triggers = List.of();
            List<Command> commands = List.of();
            if (anyApplied) {
                RuleEngine.Evaluation evaluation = ruleEngine.evaluate(written,
                        () -> store.rulesFor(report.deviceId()));
                written = evaluation.shadow();
                triggers = evaluation.triggers();
                commands = evaluation.commands();
            }
            sink.save(written);

            TelemetryResult result = new TelemetryResult(report.deviceId(), anyApplied, false,
                    newVersion, List.copyOf(outcomes), triggers, commands);
            LOG.info("[遥测结论] device=" + report.deviceId() + " version=" + newVersion
                    + " 判定依据=逐字段LWW/幂等判定 触发结果=" + summarize(result));
            return result;
        });
    }

    private static String summarize(TelemetryResult r) {
        long fired = r.triggers().stream().filter(TriggerResult::fired).count();
        return "字段接受" + r.fields().stream().filter(FieldOutcome::accepted).count()
                + " 拒绝" + r.fields().stream().filter(f -> !f.accepted()).count()
                + " 规则触发" + fired + "条 命令" + r.commands().size() + "条";
    }

    /** 规范化内容指纹：时间戳 + 字段名排序后的键值，保证乱序字段列表重复上报可判重。 */
    static String fingerprint(TelemetryReport report) {
        TreeMap<String, String> sorted = new TreeMap<>();
        report.fields().forEach((k, v) -> sorted.put(k, String.valueOf(v)));
        String raw = report.timestamp() + "|" + sorted;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
