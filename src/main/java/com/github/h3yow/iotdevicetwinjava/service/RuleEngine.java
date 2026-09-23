package com.github.h3yow.iotdevicetwinjava.service;

import com.github.h3yow.iotdevicetwinjava.model.Command;
import com.github.h3yow.iotdevicetwinjava.model.CommandState;
import com.github.h3yow.iotdevicetwinjava.model.DeviceShadow;
import com.github.h3yow.iotdevicetwinjava.model.DeviceStatus;
import com.github.h3yow.iotdevicetwinjava.model.ThresholdRule;
import com.github.h3yow.iotdevicetwinjava.model.TriggerResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * 阈值规则引擎（纯函数式，无自身状态；必须在调用方持有设备锁的上下文内运行）。
 *
 * 触发语义：
 * 1. 边沿触发：条件由“不成立/首次未知”变为“成立”才产生命令；条件持续成立不重复下发。
 * 2. 去重键 dedupKey = ruleId@version：同一规则在同一影子数据版本上至多一条命令；
 *    即使边沿状态丢失（如初始即满足），dedupKey 也能兜住重复下发。
 * 3. 设备在线：命令立即 DELIVERED；离线：命令进入 PENDING 积压，
 *    重连时由 DeviceLifecycleService 按触发版本升序补发，不改动影子数据版本。
 * 4. 规则按 ruleId 有序评估，命令 ID 由 (deviceId, ruleId, version) 确定性派生，
 *    因此同样的状态重放得到同样的命令集合。
 */
public class RuleEngine {

    private static final Logger LOG = Logger.getLogger(RuleEngine.class.getName());

    public Evaluation evaluate(DeviceShadow shadow, Supplier<List<ThresholdRule>> rulesSupplier) {
        List<ThresholdRule> rules = rulesSupplier.get();
        DeviceShadow current = shadow;
        List<TriggerResult> triggers = new ArrayList<>();
        List<Command> produced = new ArrayList<>();

        for (ThresholdRule rule : rules) {
            Object raw = current.mergedValue(rule.field());
            long evaluationTs = timestampOf(current, rule.field());
            boolean met = raw instanceof Number n && rule.operator().test(n.doubleValue(), rule.threshold());
            boolean previousState = current.ruleStates().getOrDefault(rule.ruleId(), false);

            long triggerVersion = current.version();
            String dedupKey = rule.ruleId() + "@" + triggerVersion;
            boolean alreadyDelivered = current.deliveredDedupKeys().contains(dedupKey);
            boolean pendingExists = current.pendingCommands().stream()
                    .anyMatch(c -> c.dedupKey().equals(dedupKey));

            // 边沿判定：本次成立 且 上次不成立；已下发/已积压的去重键同时兜底。
            boolean risingEdge = met && !previousState;
            boolean fire = risingEdge && !alreadyDelivered && !pendingExists;

            String basis = "field=" + rule.field()
                    + " mergedValue=" + raw
                    + " valueTs=" + evaluationTs
                    + " operator=" + rule.operator()
                    + " threshold=" + rule.threshold()
                    + " conditionMet=" + met
                    + " previousState=" + previousState
                    + " version=" + triggerVersion
                    + " dedupKey=" + dedupKey
                    + " alreadyDelivered=" + alreadyDelivered
                    + " pendingExists=" + pendingExists
                    + " => fired=" + fire;

            if (fire) {
                String commandId = "cmd-" + current.deviceId() + "-" + rule.ruleId() + "-v" + triggerVersion;
                Command command = new Command(
                        commandId, current.deviceId(), rule.ruleId(), triggerVersion, evaluationTs,
                        dedupKey, rule.commandType(),
                        rule.commandPayload() == null ? Map.of() : Map.copyOf(rule.commandPayload()),
                        CommandState.PENDING, null, null);

                List<Command> pending = new ArrayList<>(current.pendingCommands());
                List<Command> delivered = new ArrayList<>(current.deliveredCommands());
                List<String> dedupKeys = new ArrayList<>(current.deliveredDedupKeys());
                String deliveryMode = null;
                if (current.status() == DeviceStatus.ONLINE) {
                    command = command.delivered(evaluationTs, "IMMEDIATE");
                    delivered.add(command);
                    dedupKeys.add(dedupKey);
                    deliveryMode = "IMMEDIATE";
                } else {
                    pending.add(command);
                    deliveryMode = "PENDING_OFFLINE";
                }
                // 按触发版本排序积压命令，保证补发顺序确定
                pending.sort(java.util.Comparator.comparingLong(Command::triggerVersion)
                        .thenComparing(Command::dedupKey));

                Map<String, Boolean> ruleStates = new LinkedHashMap<>(current.ruleStates());
                ruleStates.put(rule.ruleId(), true);

                current = new DeviceShadow(current.deviceId(), current.deviceType(), current.version(),
                        current.reported(), current.desired(), current.status(), current.lastSeenTs(),
                        List.copyOf(pending), List.copyOf(delivered), List.copyOf(dedupKeys),
                        current.processedReportIds(), current.processedReportFingerprints(),
                        current.processedUpdates(), Map.copyOf(ruleStates));
                produced.add(command);
                LOG.info("[规则触发] device=" + current.deviceId() + " rule=" + rule.ruleId()
                        + " 输入时间戳=" + evaluationTs + " " + basis
                        + " 命令=" + commandId + " 下发方式=" + deliveryMode);
                triggers.add(new TriggerResult(rule.ruleId(), current.deviceId(), rule.field(),
                        true, true, commandId, triggerVersion, basis + " delivery=" + deliveryMode));
            } else {
                // 无论是否触发，都同步最新的条件成立状态（false -> true / true -> false）
                if (met != previousState) {
                    Map<String, Boolean> ruleStates = new LinkedHashMap<>(current.ruleStates());
                    ruleStates.put(rule.ruleId(), met);
                    current = new DeviceShadow(current.deviceId(), current.deviceType(), current.version(),
                            current.reported(), current.desired(), current.status(), current.lastSeenTs(),
                            current.pendingCommands(), current.deliveredCommands(), current.deliveredDedupKeys(),
                            current.processedReportIds(), current.processedReportFingerprints(),
                            current.processedUpdates(), Map.copyOf(ruleStates));
                }
                String whyNot;
                if (!met) {
                    whyNot = "条件不成立";
                } else if (previousState) {
                    whyNot = "条件已处于成立状态(非上升沿)，不重复触发";
                } else if (alreadyDelivered) {
                    whyNot = "去重键已下发，跳过";
                } else {
                    whyNot = "离线积压已存在同键命令，跳过";
                }
                LOG.info("[规则评估] device=" + current.deviceId() + " rule=" + rule.ruleId()
                        + " 输入时间戳=" + evaluationTs + " " + basis + " 结论: " + whyNot);
                triggers.add(new TriggerResult(rule.ruleId(), current.deviceId(), rule.field(),
                        met, false, null, triggerVersion, basis + " => " + whyNot));
            }
        }
        return new Evaluation(current, List.copyOf(triggers), List.copyOf(produced));
    }

    private long timestampOf(DeviceShadow shadow, String field) {
        var d = shadow.desired().get(field);
        if (d != null) {
            return d.timestamp();
        }
        var r = shadow.reported().get(field);
        return r == null ? -1L : r.timestamp();
    }

    /** 评估结果：新影子（copy-on-write）+ 规则结论 + 本次新产生命令。 */
    public record Evaluation(DeviceShadow shadow, List<TriggerResult> triggers, List<Command> commands) {
    }
}
