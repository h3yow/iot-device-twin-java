package com.github.h3yow.iotdevicetwinjava.model;

import java.util.List;

/**
 * 影子更新结论。committed=true 时 newVersion 为新版本；
 * 为 false 时 currentVersion 为服务端当前版本，reasons 列出每字段拒绝原因。
 */
public record ShadowUpdateResult(String deviceId, boolean committed, long currentVersion,
                                long newVersion, boolean idempotentReplay,
                                List<FieldOutcome> fields) {
}
