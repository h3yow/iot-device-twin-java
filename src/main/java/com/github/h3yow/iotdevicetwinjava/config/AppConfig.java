package com.github.h3yow.iotdevicetwinjava.config;

import com.github.h3yow.iotdevicetwinjava.service.DeviceLifecycleService;
import com.github.h3yow.iotdevicetwinjava.service.RuleEngine;
import com.github.h3yow.iotdevicetwinjava.service.ShadowService;
import com.github.h3yow.iotdevicetwinjava.service.TelemetryService;
import com.github.h3yow.iotdevicetwinjava.store.DeviceShadowStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 本地内存装配：所有状态保存在 DeviceShadowStore 单例中。 */
@Configuration
public class AppConfig {

    @Bean
    public DeviceShadowStore deviceShadowStore() {
        return new DeviceShadowStore();
    }

    @Bean
    public RuleEngine ruleEngine() {
        return new RuleEngine();
    }

    @Bean
    public TelemetryService telemetryService(DeviceShadowStore store, RuleEngine ruleEngine) {
        return new TelemetryService(store, ruleEngine);
    }

    @Bean
    public ShadowService shadowService(DeviceShadowStore store, RuleEngine ruleEngine) {
        return new ShadowService(store, ruleEngine);
    }

    @Bean
    public DeviceLifecycleService deviceLifecycleService(DeviceShadowStore store) {
        return new DeviceLifecycleService(store);
    }
}
