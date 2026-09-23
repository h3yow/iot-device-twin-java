package com.github.h3yow.iotdevicetwinjava.model;

import java.util.Map;

/**
 * 阈值规则：当影子中指定指标满足比较条件时触发一次命令下发。
 * 条件在一次“持续为真”期间只触发一次，重新变假后才再次武装。
 */
public record RuleDefinition(String ruleId,
                             String metric,
                             RuleOperator operator,
                             double threshold,
                             String commandName,
                             Map<String, Object> commandPayload) {
}
