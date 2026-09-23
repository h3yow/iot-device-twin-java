package com.github.h3yow.iotdevicetwinjava.model;

import java.util.Map;

/**
 * 阈值规则：当 merged 视图中 field 满足 operator(threshold) 时触发命令。
 * 触发为边沿语义：仅在条件由“不满足 -> 满足”（含首次进入）时下发一次，
 * 同一影子版本下并发评估只下发一次。
 */
public record ThresholdRule(String ruleId, String deviceId, String field,
                            Operator operator, double threshold,
                            String commandType, Map<String, Object> commandPayload) {

    public boolean appliesTo(String targetDeviceId) {
        return deviceId == null || deviceId.equals(targetDeviceId);
    }
}
