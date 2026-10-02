package com.github.h3yow.iotdevicetwinjava.config;

import com.github.h3yow.iotdevicetwinjava.service.BatchDispatchService;
import com.github.h3yow.iotdevicetwinjava.service.DeviceLifecycleService;
import com.github.h3yow.iotdevicetwinjava.service.RuleEngine;
import com.github.h3yow.iotdevicetwinjava.service.ShadowService;
import com.github.h3yow.iotdevicetwinjava.service.TelemetryService;
import com.github.h3yow.iotdevicetwinjava.store.DeviceShadowStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 本地内存装配：所有状态保存在 DeviceShadowStore 单例中。 */
@Configuration
public class AppConfig {

    /** 单批下发设备数上限，可通过应用属性 iot.batch.max-size 覆盖。 */
    @Bean
    public BatchDispatchService batchDispatchService(DeviceShadowStore store,
                                                     ShadowService shadowService,
                                                     @Value("${iot.batch.max-size:500}") int maxBatchSize) {
        return new BatchDispatchService(store, shadowService, maxBatchSize);
    }

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
