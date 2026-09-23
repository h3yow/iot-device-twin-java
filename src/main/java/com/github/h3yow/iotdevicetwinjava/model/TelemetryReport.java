package com.github.h3yow.iotdevicetwinjava.model;

import java.util.Map;

/**
 * 设备遥测上报。
 *
 * @param deviceId    设备 ID
 * @param timestamp   设备侧采集时间戳（epoch millis）
 * @param metrics     指标键值
 * @param clientToken 幂等令牌，可为空；相同令牌的重复上报只生效一次
 */
public record TelemetryReport(String deviceId,
                              long timestamp,
                              Map<String, Object> metrics,
                              String clientToken) {
}
