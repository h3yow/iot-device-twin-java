package com.github.h3yow.iotdevicetwinjava.web;

import com.github.h3yow.iotdevicetwinjava.model.ThresholdRule;
import com.github.h3yow.iotdevicetwinjava.store.DeviceShadowStore;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 阈值规则管理接口（本地演示用）。 */
@RestController
@RequestMapping("/api/rules")
public class RuleAdminController {

    private final DeviceShadowStore store;

    public RuleAdminController(DeviceShadowStore store) {
        this.store = store;
    }

    @PostMapping
    public ThresholdRule add(@RequestBody ThresholdRule rule) {
        store.addRule(rule);
        return rule;
    }

    @GetMapping
    public List<ThresholdRule> list(@RequestParam(required = false) String deviceId) {
        return deviceId == null ? store.allShadows().stream()
                .flatMap(sh -> store.rulesFor(sh.deviceId()).stream())
                .distinct().toList() : store.rulesFor(deviceId);
    }
}
