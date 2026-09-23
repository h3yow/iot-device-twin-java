package com.github.h3yow.iotdevicetwinjava.store;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则触发状态：每个 (设备, 规则) 维护“是否已武装(armed)”。
 *
 * 语义（边沿触发 + 去重）：
 * - armed=true 且条件为真  -> 触发一次命令，随即置 false；
 * - armed=false 期间条件持续为真 -> 不再触发（防止并发/重复下发）；
 * - 条件变假              -> 重新置 true，下次再变真时才会再次触发。
 *
 * 所有访问都在设备锁内发生，这里仅做状态保存。
 */
@Component
public class RuleStateStore {

    private record Key(String deviceId, String ruleId) {
    }

    private final ConcurrentHashMap<Key, Boolean> armed = new ConcurrentHashMap<>();

    /** 规则是否处于武装状态（可触发）。无记录视为默认武装。 */
    public boolean isArmed(String deviceId, String ruleId) {
        return armed.getOrDefault(new Key(deviceId, ruleId), Boolean.TRUE);
    }

    public void setArmed(String deviceId, String ruleId, boolean value) {
        armed.put(new Key(deviceId, ruleId), value);
    }
}
