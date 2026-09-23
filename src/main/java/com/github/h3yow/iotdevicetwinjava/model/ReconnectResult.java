package com.github.h3yow.iotdevicetwinjava.model;

import java.util.List;

/**
 * 设备离线 -> 重连恢复的结论：补发期间积压的命令，按触发版本升序补发，
 * 已下发的（dedupKey 命中）绝不重复；补发不改变影子版本与任何字段状态。
 */
public record ReconnectResult(String deviceId, long shadowVersion,
                              List<Command> redeliveredCommands,
                              List<String> skippedDedupKeys) {
}
