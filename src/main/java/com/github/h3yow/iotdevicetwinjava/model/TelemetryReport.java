package com.github.h3yow.iotdevicetwinjava.model;

import java.util.Map;

/**
 * 设备遥测上报。
 *
 * @param reportId 客户端幂等标识；为空时以 (timestamp, fields) 内容指纹判重
 */
public record TelemetryReport(String deviceId, long timestamp, String reportId,
                              Map<String, Object> fields) {
}
