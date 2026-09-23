package com.github.h3yow.iotdevicetwinjava.store;

import com.github.h3yow.iotdevicetwinjava.model.UpdateOutcome;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等记录：以 (业务类型, 设备, clientToken) 为键缓存首次结论。
 * 相同令牌重放直接返回首次结论，不重复累计、不重复触发副作用。
 */
@Component
public class IdempotencyStore {

    public record Key(String kind, String deviceId, String clientToken) {
    }

    private final ConcurrentHashMap<Key, UpdateOutcome> outcomes = new ConcurrentHashMap<>();

    public UpdateOutcome get(Key key) {
        return outcomes.get(key);
    }

    public void put(Key key, UpdateOutcome outcome) {
        outcomes.put(key, outcome);
    }
}
