package com.github.h3yow.iotdevicetwinjava.store;

import com.github.h3yow.iotdevicetwinjava.model.RuleDefinition;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/** 已注册阈值规则（内存）。 */
@Component
public class RuleDefinitionStore {

    private final ConcurrentHashMap<String, RuleDefinition> rules = new ConcurrentHashMap<>();

    public void register(RuleDefinition rule) {
        rules.put(rule.ruleId(), rule);
    }

    public Collection<RuleDefinition> all() {
        return rules.values();
    }
}
