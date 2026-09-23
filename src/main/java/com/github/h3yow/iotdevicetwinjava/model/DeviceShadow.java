package com.github.h3yow.iotdevicetwinjava.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备影子聚合根（不可变状态快照，copy-on-write 更新）。
 *
 * version                   数据版本：reported/desired 每次成功写入单调 +1
 * reported                  设备上报区（遥测按字段 LWW 写入）
 * desired                   期望区（版本化合并写入）
 * status                    在线状态（不占用数据版本号）
 * lastSeenTs                最近一次被接受上报的时间戳
 * pendingCommands           离线期间积压、待重连补发的命令
 * deliveredCommands         已下发（含补发）命令历史
 * deliveredDedupKeys        已下发命令去重键 ruleId@version，杜绝同条件重复下发
 * processedReportIds        reportId -> 处理后版本，整批幂等重放
 * processedReportFingerprints 内容指纹 -> 处理后版本，无 reportId 时按内容判重
 * processedUpdates          updateId -> 更新结论，影子更新重放结论一致
 * ruleStates                ruleId -> 上一次评估时条件是否成立，支撑边沿触发
 */
public record DeviceShadow(String deviceId, String deviceType, long version,
                           Map<String, TaggedValue> reported,
                           Map<String, TaggedValue> desired,
                           DeviceStatus status, long lastSeenTs,
                           List<Command> pendingCommands,
                           List<Command> deliveredCommands,
                           List<String> deliveredDedupKeys,
                           Map<String, Long> processedReportIds,
                           Map<String, Long> processedReportFingerprints,
                           Map<String, ShadowUpdateResult> processedUpdates,
                           Map<String, Boolean> ruleStates) {

    public static DeviceShadow initialized(String deviceId, String deviceType) {
        return new DeviceShadow(deviceId, deviceType, 0L,
                new LinkedHashMap<>(), new LinkedHashMap<>(), DeviceStatus.OFFLINE, 0L,
                List.of(), List.of(), List.of(),
                new LinkedHashMap<>(), new LinkedHashMap<>(),
                new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    /** 合并视图：desired 优先，缺省回退 reported，供规则评估读取。 */
    public Object mergedValue(String field) {
        TaggedValue d = desired.get(field);
        if (d != null) {
            return d.value();
        }
        TaggedValue r = reported.get(field);
        return r == null ? null : r.value();
    }
}
