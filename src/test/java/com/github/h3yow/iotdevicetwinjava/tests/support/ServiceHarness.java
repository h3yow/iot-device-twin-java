package com.github.h3yow.iotdevicetwinjava.tests.support;

import com.github.h3yow.iotdevicetwinjava.model.FieldType;
import com.github.h3yow.iotdevicetwinjava.model.DeviceSchema;
import com.github.h3yow.iotdevicetwinjava.model.ThresholdRule;
import com.github.h3yow.iotdevicetwinjava.service.BatchShadowService;
import com.github.h3yow.iotdevicetwinjava.service.DeviceLifecycleService;
import com.github.h3yow.iotdevicetwinjava.service.RuleEngine;
import com.github.h3yow.iotdevicetwinjava.service.ShadowService;
import com.github.h3yow.iotdevicetwinjava.service.TelemetryService;
import com.github.h3yow.iotdevicetwinjava.store.DeviceShadowStore;

import java.util.Map;
import java.util.logging.Logger;

/**
 * 每个测试用例独立的一套本地内存服务，避免用例间状态污染。
 */
public final class ServiceHarness {

    private static final Logger LOG = Logger.getLogger(ServiceHarness.class.getName());

    public final DeviceShadowStore store = new DeviceShadowStore();
    public final RuleEngine ruleEngine = new RuleEngine();
    public final TelemetryService telemetry = new TelemetryService(store, ruleEngine);
    public final ShadowService shadows = new ShadowService(store, ruleEngine);
    public final BatchShadowService batchShadows =
            new BatchShadowService(store, shadows, BatchShadowService.defaultDeviceExecutor());
    public final DeviceLifecycleService lifecycle = new DeviceLifecycleService(store);

    public static final DeviceSchema DEFAULT_SCHEMA = new DeviceSchema("default-thermostat", 1,
            Map.of("temperature", FieldType.NUMBER,
                    "humidity", FieldType.NUMBER,
                    "mode", FieldType.STRING,
                    "enabled", FieldType.BOOLEAN));

    /** 注册设备（默认 schema）并上线。 */
    public void registerOnline(String deviceId, long atTs) {
        lifecycle.register(deviceId, DEFAULT_SCHEMA.deviceType());
        lifecycle.markOnline(deviceId, atTs);
    }

    public void registerOffline(String deviceId) {
        lifecycle.register(deviceId, DEFAULT_SCHEMA.deviceType());
    }

    public ThresholdRule addRule(String ruleId, String deviceId, String field,
                                 com.github.h3yow.iotdevicetwinjava.model.Operator op,
                                 double threshold, String commandType) {
        ThresholdRule rule = new ThresholdRule(ruleId, deviceId, field, op, threshold,
                commandType, Map.of("target", threshold));
        store.addRule(rule);
        return rule;
    }

    public static void caseHeader(String title) {
        LOG.info("================ 测试场景: " + title + " ================");
    }
}
