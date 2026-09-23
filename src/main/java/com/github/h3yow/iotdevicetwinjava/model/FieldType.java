package com.github.h3yow.iotdevicetwinjava.model;

/**
 * 设备配置模式（schema）中允许的字段类型。
 * 类型判定只看值本身，与 JSON 反序列化的宽松数值类型兼容。
 */
public enum FieldType {
    NUMBER,
    STRING,
    BOOLEAN;

    public boolean matches(Object value) {
        if (value == null) {
            return false;
        }
        return switch (this) {
            case NUMBER -> value instanceof Number;
            case STRING -> value instanceof String;
            case BOOLEAN -> value instanceof Boolean;
        };
    }

    public String describe(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
