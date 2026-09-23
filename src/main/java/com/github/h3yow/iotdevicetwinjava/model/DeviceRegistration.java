package com.github.h3yow.iotdevicetwinjava.model;

/**
 * 设备注册信息：设备与其配置模式（schema）。
 */
public record DeviceRegistration(String deviceId, String deviceType, DeviceSchema schema) {
}
