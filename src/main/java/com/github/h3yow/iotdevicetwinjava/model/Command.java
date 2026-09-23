package com.github.h3yow.iotdevicetwinjava.model;

import java.util.Map;

/**
 * 规则触发后下发（或排队待补发）的设备命令。
 *
 * @param commandId 全局唯一命令 ID，也是去重幂等键
 * @param deviceId  目标设备
 * @param ruleId    产生该命令的规则
 * @param name      命令名
 * @param payload   命令载荷
 * @param createdAt 创建时间戳
 * @param delivered 是否已送达设备
 */
public record Command(String commandId,
                      String deviceId,
                      String ruleId,
                      String name,
                      Map<String, Object> payload,
                      long createdAt,
                      boolean delivered) {
}
