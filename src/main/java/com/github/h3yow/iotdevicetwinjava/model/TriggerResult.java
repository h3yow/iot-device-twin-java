package com.github.h3yow.iotdevicetwinjava.model;

/**
 * 单条规则评估结论，basis 记录输入时间戳、取值与比较过程，供日志解释。
 */
public record TriggerResult(String ruleId, String deviceId, String field,
                            boolean conditionMet, boolean fired, String commandId,
                            long evaluatedVersion, String basis) {
}
