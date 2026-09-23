package com.github.h3yow.iotdevicetwinjava.model;

import java.util.List;

/**
 * 一次遥测上报的整体结论。
 * idempotentReplay=true 表示整批数据此前已处理过，本次重放不再产生任何副作用。
 * triggers/commands 为状态写入后规则评估与命令下发结论（重放时为空）。
 */
public record TelemetryResult(String deviceId, boolean applied, boolean idempotentReplay,
                              long resultingVersion, List<FieldOutcome> fields,
                              List<TriggerResult> triggers, List<Command> commands) {
}
