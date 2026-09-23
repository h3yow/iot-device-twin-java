package com.github.h3yow.iotdevicetwinjava.model;

import java.util.Map;

/**
 * 设备配置模式：允许字段名 -> 类型。
 * schemaVersion 用于配置兼容：影子更新只能针对不低于某代配置的字段集合，
 * 未知字段按 UNKNOWN_FIELD 拒绝。
 */
public record DeviceSchema(String deviceType, int schemaVersion, Map<String, FieldType> fields) {
}
