package com.github.h3yow.iotdevicetwinjava.model;

import java.util.Map;

/**
 * 规则触发产生的命令。
 *
 * dedupKey = ruleId + "@" + triggerVersion：同一规则在同一影子版本上只允许一条命令。
 * state     PENDING（设备离线积压）或 DELIVERED（已下发/补发）。
 */
public record Command(String commandId, String deviceId, String ruleId,
                      long triggerVersion, long triggerTs, String dedupKey,
                      String commandType, Map<String, Object> payload,
                      CommandState state, Long deliveredAt, String deliveryMode) {

    public Command delivered(long at, String mode) {
        return new Command(commandId, deviceId, ruleId, triggerVersion, triggerTs,
                dedupKey, commandType, payload, CommandState.DELIVERED, at, mode);
    }
}
