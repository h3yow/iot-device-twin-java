package com.github.h3yow.iotdevicetwinjava.service;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 字段/指标类型注册表：定义允许上报的指标名及其期望类型。
 *
 * 判定规则（对外可区分）：
 * - 字段未注册            -> 上层拒绝，原因 UNKNOWN_FIELD
 * - 值类型与声明不兼容    -> 上层拒绝，原因 TYPE_MISMATCH
 *
 * 数值字段统一声明为 double，Integer/Long/Double/Float 均兼容（归一化为 double）；
 * 其余按精确类型匹配（Boolean、String）。
 */
@Component
public class FieldSchemaRegistry {

    private final Map<String, Class<?>> fields = new LinkedHashMap<>();

    public FieldSchemaRegistry() {
        // 默认内置指标（模拟设备温度、湿度、开关、固件版本）
        register("temperature", double.class);
        register("humidity", double.class);
        register("powerOn", boolean.class);
        register("firmwareVersion", String.class);
    }

    /** 注册或覆盖一个字段定义（配置兼容：字段集可演进，旧字段保留）。 */
    public synchronized void register(String field, Class<?> type) {
        // 原始类型归一化为包装类型：boolean.class.isInstance(Boolean.TRUE) 恒为 false
        Class<?> boxed = boxed(type);
        fields.put(field, boxed);
    }

    /** 字段是否已注册。 */
    public synchronized boolean isKnown(String field) {
        return fields.containsKey(field);
    }

    /** 取值类型是否与字段声明类型兼容。 */
    public synchronized boolean isTypeCompatible(String field, Object value) {
        Class<?> declared = fields.get(field);
        if (declared == null) {
            return false;
        }
        if (value == null) {
            return false;
        }
        if (declared == Double.class) {
            return value instanceof Number;
        }
        return declared.isInstance(value);
    }

    /**
     * 将值归一化为字段声明类型；调用前必须已通过 isTypeCompatible 校验。
     */
    public synchronized Object normalize(String field, Object value) {
        Class<?> declared = fields.get(field);
        if (declared == Double.class) {
            return ((Number) value).doubleValue();
        }
        return value;
    }

    public synchronized Set<String> knownFields() {
        return Collections.unmodifiableSet(fields.keySet());
    }

    private static Class<?> boxed(Class<?> type) {
        if (type == double.class) {
            return Double.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        return type;
    }
}
