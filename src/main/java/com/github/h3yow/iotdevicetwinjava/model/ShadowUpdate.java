package com.github.h3yow.iotdevicetwinjava.model;

import java.util.Map;

/**
 * 对 desired 区的版本化影子更新（如应用端下发期望配置）。
 *
 * @param baseVersion 客户端读取时的版本号；落后于当前版本则以 STALE_VERSION 拒绝
 * @param updateId    幂等键；同 updateId 重放必须得到完全一致的结论
 */
public record ShadowUpdate(String deviceId, long baseVersion, String updateId,
                           long timestamp, Map<String, Object> desired) {
}
