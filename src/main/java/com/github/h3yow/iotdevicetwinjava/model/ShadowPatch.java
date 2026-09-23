package com.github.h3yow.iotdevicetwinjava.model;

import java.util.Map;

/**
 * 影子期望状态的版本化更新（整批原子校验、原子生效）。
 *
 * @param deviceId    设备 ID
 * @param baseVersion 客户端所基于的影子版本；落后于当前版本则整批拒绝
 * @param timestamp   更新时间戳（epoch millis）
 * @param fields      期望字段键值
 * @param clientToken 幂等令牌，可为空；相同令牌重放返回首次结论
 */
public record ShadowPatch(String deviceId,
                          int baseVersion,
                          long timestamp,
                          Map<String, Object> fields,
                          String clientToken) {
}
