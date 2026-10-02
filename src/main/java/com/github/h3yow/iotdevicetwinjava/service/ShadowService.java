package com.github.h3yow.iotdevicetwinjava.service;

import com.github.h3yow.iotdevicetwinjava.model.DeviceShadow;
import com.github.h3yow.iotdevicetwinjava.model.FieldOutcome;
import com.github.h3yow.iotdevicetwinjava.model.FieldType;
import com.github.h3yow.iotdevicetwinjava.model.RejectReason;
import com.github.h3yow.iotdevicetwinjava.model.ShadowUpdate;
import com.github.h3yow.iotdevicetwinjava.model.ShadowUpdateResult;
import com.github.h3yow.iotdevicetwinjava.model.TaggedValue;
import com.github.h3yow.iotdevicetwinjava.model.TriggerResult;
import com.github.h3yow.iotdevicetwinjava.store.DeviceShadowStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 影子 desired 区版本化合并服务。
 *
 * 合并与拒绝规则（原子批量，全部在设备锁内完成）：
 * 1. 乐观版本：update.baseVersion 必须等于当前 version，否则整批以 STALE_VERSION 拒绝
 *    （返回当前版本供客户端重读），不做任何部分写入。
 * 2. schema 兼容：未知字段 UNKNOWN_FIELD、类型不匹配 TYPE_MISMATCH，逐字段给出原因，
 *    与 STALE_VERSION 可区分；只要存在任何非法字段，整批不提交。
 * 3. 幂等重放：同一 updateId + 同一请求体重放，返回首次的完整结论
 *    （idempotentReplay=true），不重复写入、不重复触发；同 updateId 但请求体不同
 *    以 UPDATE_ID_CONFLICT 拒绝。
 * 4. 成功提交后 version +1，随后驱动一次规则评估（desired 也参与 merged 视图）。
 */
public class ShadowService {

    private static final Logger LOG = Logger.getLogger(ShadowService.class.getName());

    private final DeviceShadowStore store;
    private final RuleEngine ruleEngine;

    /** "(deviceId, updateId)" -> 首次请求体签名。设备锁保证同设备串行，键按设备作用域隔离，
     *  不同设备使用相同 updateId 也不会互相串判定。 */
    private final Map<String, String> updatePayloadSignatures = new ConcurrentHashMap<>();

    private static String signatureKey(String deviceId, String updateId) {
        return deviceId + "#" + updateId;
    }

    public ShadowService(DeviceShadowStore store, RuleEngine ruleEngine) {
        this.store = store;
        this.ruleEngine = ruleEngine;
    }

    public ShadowUpdateResult applyUpdate(ShadowUpdate update) {
        LOG.info("[影子更新] device=" + update.deviceId() + " updateId=" + update.updateId()
                + " baseVersion=" + update.baseVersion() + " 输入时间戳=" + update.timestamp()
                + " desired=" + new TreeMap<>(update.desired()));

        return store.transact(update.deviceId(), (shadow, sink) ->
                decide(update, update.baseVersion(), shadow, sink));
    }

    /**
     * 批量下发内部入口：baseVersion 取设备锁内的当前版本（调用方不感知版本号），
     * 因此批量重放/多批次并发到达同一设备时由设备锁串行合并，不会产生无谓的
     * STALE_VERSION；判定、记忆、规则触发与单台 {@link #applyUpdate} 完全同一份逻辑，
     * 保证单台与批量对同一台设备重放同一份内容结论一致。
     */
    public ShadowUpdateResult applyUpdateOnCurrentVersion(ShadowUpdate update) {
        LOG.info("[影子更新-批量] device=" + update.deviceId() + " updateId=" + update.updateId()
                + " baseVersion=(取当前版本)" + " 输入时间戳=" + update.timestamp()
                + " desired=" + new TreeMap<>(update.desired()));

        return store.transact(update.deviceId(), (shadow, sink) ->
                decide(update, shadow.version(), shadow, sink));
    }

    /** 在设备锁内完成幂等键判定 -> 版本/schema 校验 -> 提交或拒绝并记忆。 */
    private ShadowUpdateResult decide(ShadowUpdate update, long baseVersion,
                                      DeviceShadow shadow, DeviceShadowStore.Sink sink) {
            var registration = store.registration(update.deviceId()).orElseThrow();
            String signature = payloadSignature(update);
            ShadowUpdateResult first = shadow.processedUpdates().get(update.updateId());

            // ---- 幂等键相关判定 ----
            if (first != null) {
                String firstSignature = updatePayloadSignatures.get(
                        signatureKey(update.deviceId(), update.updateId()));
                if (!signature.equals(firstSignature)) {
                    LOG.info("[影子更新结论] 判定依据=UPDATE_ID_CONFLICT(updateId 已用于其他请求体)"
                            + " 触发结果=拒绝，无副作用");
                    return new ShadowUpdateResult(update.deviceId(), false, shadow.version(),
                            shadow.version(), false, List.of(FieldOutcome.rejected("*", null,
                            update.timestamp(), null, null, RejectReason.UPDATE_ID_CONFLICT,
                            "updateId=" + update.updateId() + " 已用于不同的更新请求体，拒绝复用")));
                }
                LOG.info("[影子更新结论] 判定依据=updateId 幂等重放(首次 committed="
                        + first.committed() + " newVersion=" + first.newVersion()
                        + ") 触发结果=回放首次结论，无副作用");
                return new ShadowUpdateResult(first.deviceId(), first.committed(),
                        shadow.version(), first.newVersion(), true, first.fields());
            }

            // ---- 版本乐观锁检查（整批） ----
            List<FieldOutcome> outcomes = new ArrayList<>();
            boolean staleVersion = baseVersion != shadow.version();
            if (staleVersion) {
                outcomes.add(FieldOutcome.rejected("*", null, update.timestamp(), null, null,
                        RejectReason.STALE_VERSION,
                        "baseVersion=" + baseVersion + " 不等于当前版本 currentVersion="
                                + shadow.version() + "，整批拒绝（请重读影子后合并）"));
            }

            // ---- 逐字段 schema 校验（版本过期时以 STALE_VERSION 为准，字段原因不重复罗列） ----
            if (!staleVersion) {
                for (Map.Entry<String, Object> entry : new TreeMap<>(update.desired()).entrySet()) {
                    String field = entry.getKey();
                    Object value = entry.getValue();
                    FieldType declared = registration.schema().fields().get(field);
                    TaggedValue current = shadow.desired().get(field);
                    if (declared == null) {
                        outcomes.add(FieldOutcome.rejected(field, value, update.timestamp(),
                                current == null ? null : current.value(),
                                current == null ? null : current.timestamp(),
                                RejectReason.UNKNOWN_FIELD,
                                "字段 '" + field + "' 不在 schema v" + registration.schema().schemaVersion()
                                        + " 允许集合 " + registration.schema().fields().keySet()));
                    } else if (!declared.matches(value)) {
                        outcomes.add(FieldOutcome.rejected(field, value, update.timestamp(),
                                current == null ? null : current.value(),
                                current == null ? null : current.timestamp(),
                                RejectReason.TYPE_MISMATCH,
                                "字段 '" + field + "' 声明类型=" + declared + "，实际类型="
                                        + declared.describe(value)));
                    } else {
                        outcomes.add(new FieldOutcome(field, true, value, update.timestamp(),
                                current == null ? null : current.value(),
                                current == null ? null : current.timestamp(), null,
                                "schema 校验通过，将合并进 desired @ v" + (shadow.version() + 1)));
                    }
                }
            }

            boolean valid = outcomes.stream().allMatch(FieldOutcome::accepted);
            if (!valid) {
                ShadowUpdateResult rejected = new ShadowUpdateResult(update.deviceId(), false,
                        shadow.version(), shadow.version(), false, List.copyOf(outcomes));
                remember(sink, shadow, update, rejected, signature);
                LOG.info("[影子更新结论] version 保持=" + shadow.version() + " 判定依据="
                        + outcomeReasons(rejected) + " 触发结果=整批拒绝，无副作用");
                return rejected;
            }

            // ---- 提交：合并 desired，version +1 ----
            Map<String, TaggedValue> newDesired = new LinkedHashMap<>(shadow.desired());
            for (Map.Entry<String, Object> entry : update.desired().entrySet()) {
                newDesired.put(entry.getKey(),
                        new TaggedValue(entry.getValue(), update.timestamp(), "desired-update"));
            }
            long newVersion = shadow.version() + 1;
            DeviceShadow written = new DeviceShadow(shadow.deviceId(), shadow.deviceType(), newVersion,
                    shadow.reported(), Map.copyOf(newDesired), shadow.status(), shadow.lastSeenTs(),
                    shadow.pendingCommands(), shadow.deliveredCommands(), shadow.deliveredDedupKeys(),
                    shadow.processedReportIds(), shadow.processedReportFingerprints(),
                    shadow.processedUpdates(), shadow.ruleStates());

            RuleEngine.Evaluation evaluation = ruleEngine.evaluate(written,
                    () -> store.rulesFor(update.deviceId()));
            written = evaluation.shadow();
            ShadowUpdateResult committed = new ShadowUpdateResult(update.deviceId(), true,
                    shadow.version(), newVersion, false, List.copyOf(outcomes));
            remember(sink, written, update, committed, signature);

            long fired = evaluation.triggers().stream().filter(TriggerResult::fired).count();
            LOG.info("[影子更新结论] v" + shadow.version() + " -> v" + newVersion
                    + " 判定依据=版本匹配且字段全部合法 触发结果=规则触发" + fired + "条 命令"
                    + evaluation.commands().size() + "条");
            return committed;
    }

    private String outcomeReasons(ShadowUpdateResult r) {
        return r.fields().stream()
                .filter(f -> f.reason() != null)
                .map(f -> f.field() + ":" + f.reason())
                .toList().toString();
    }

    private void remember(DeviceShadowStore.Sink sink, DeviceShadow shadow, ShadowUpdate update,
                          ShadowUpdateResult result, String signature) {
        Map<String, ShadowUpdateResult> remembered =
                new LinkedHashMap<>(shadow.processedUpdates());
        remembered.put(update.updateId(), result);
        DeviceShadow withMemory = new DeviceShadow(shadow.deviceId(), shadow.deviceType(),
                shadow.version(), shadow.reported(), shadow.desired(), shadow.status(),
                shadow.lastSeenTs(), shadow.pendingCommands(), shadow.deliveredCommands(),
                shadow.deliveredDedupKeys(), shadow.processedReportIds(),
                shadow.processedReportFingerprints(), Map.copyOf(remembered), shadow.ruleStates());
        updatePayloadSignatures.put(signatureKey(update.deviceId(), update.updateId()), signature);
        sink.save(withMemory);
    }

    /**
     * 请求体签名只包含“内容”（时间戳 + 排序后的 desired），不包含 baseVersion：
     * 批量入口在设备锁内取当前版本，同内容重放时当前版本可能已被自己推进，
     * 若把版本计入签名会把幂等重放误判为 UPDATE_ID_CONFLICT。
     */
    private static String payloadSignature(ShadowUpdate update) {
        return update.timestamp() + "|" + new TreeMap<>(update.desired());
    }
}
