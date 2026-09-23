package com.github.h3yow.iotdevicetwinjava.controller;

import com.github.h3yow.iotdevicetwinjava.dto.ShadowView;
import com.github.h3yow.iotdevicetwinjava.model.RuleDefinition;
import com.github.h3yow.iotdevicetwinjava.model.RuleOperator;
import com.github.h3yow.iotdevicetwinjava.model.ShadowPatch;
import com.github.h3yow.iotdevicetwinjava.model.TelemetryReport;
import com.github.h3yow.iotdevicetwinjava.model.UpdateOutcome;
import com.github.h3yow.iotdevicetwinjava.service.DeviceConnectionService;
import com.github.h3yow.iotdevicetwinjava.service.ShadowUpdateService;
import com.github.h3yow.iotdevicetwinjava.service.TelemetryService;
import com.github.h3yow.iotdevicetwinjava.store.DeviceStore;
import com.github.h3yow.iotdevicetwinjava.store.RuleDefinitionStore;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备接入与设备影子的本地 REST 入口。
 *
 * POST /api/devices/{id}/telemetry   遥测上报
 * POST /api/devices/{id}/shadow      版本化影子补丁
 * GET  /api/devices/{id}/shadow      影子快照
 * POST /api/devices/{id}/connect     上线/重连（返回补发命令）
 * POST /api/devices/{id}/disconnect  离线
 * POST /api/rules                    注册阈值规则
 */
@RestController
public class DeviceTwinController {

    private final TelemetryService telemetryService;
    private final ShadowUpdateService shadowUpdateService;
    private final DeviceConnectionService connectionService;
    private final DeviceStore deviceStore;
    private final RuleDefinitionStore ruleDefinitionStore;

    public DeviceTwinController(TelemetryService telemetryService,
                                ShadowUpdateService shadowUpdateService,
                                DeviceConnectionService connectionService,
                                DeviceStore deviceStore,
                                RuleDefinitionStore ruleDefinitionStore) {
        this.telemetryService = telemetryService;
        this.shadowUpdateService = shadowUpdateService;
        this.connectionService = connectionService;
        this.deviceStore = deviceStore;
        this.ruleDefinitionStore = ruleDefinitionStore;
    }

    @PostMapping("/api/devices/{deviceId}/telemetry")
    public UpdateOutcome report(@PathVariable String deviceId, @RequestBody Map<String, Object> body) {
        long timestamp = ((Number) body.getOrDefault("timestamp", 0L)).longValue();
        String token = (String) body.get("clientToken");
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) body.getOrDefault("metrics", Map.of());
        return telemetryService.report(new TelemetryReport(deviceId, timestamp,
                new LinkedHashMap<>(metrics), token));
    }

    @PostMapping("/api/devices/{deviceId}/shadow")
    public UpdateOutcome patch(@PathVariable String deviceId, @RequestBody Map<String, Object> body) {
        int baseVersion = ((Number) body.getOrDefault("baseVersion", 0)).intValue();
        long timestamp = ((Number) body.getOrDefault("timestamp", 0L)).longValue();
        String token = (String) body.get("clientToken");
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) body.getOrDefault("fields", Map.of());
        return shadowUpdateService.applyPatch(new ShadowPatch(deviceId, baseVersion, timestamp,
                new LinkedHashMap<>(fields), token));
    }

    @GetMapping("/api/devices/{deviceId}/shadow")
    public ShadowView shadow(@PathVariable String deviceId) {
        var shadow = deviceStore.get(deviceId);
        return shadow == null ? null : ShadowView.of(shadow);
    }

    @PostMapping("/api/devices/{deviceId}/connect")
    public Map<String, Object> connect(@PathVariable String deviceId) {
        List<?> redelivered = connectionService.reconnect(deviceId);
        return Map.of("deviceId", deviceId, "status", "ONLINE", "redelivered", redelivered);
    }

    @PostMapping("/api/devices/{deviceId}/disconnect")
    public Map<String, Object> disconnect(@PathVariable String deviceId) {
        connectionService.markOffline(deviceId);
        return Map.of("deviceId", deviceId, "status", "OFFLINE");
    }

    @PostMapping("/api/rules")
    public Map<String, Object> registerRule(@RequestBody Map<String, Object> body) {
        String ruleId = (String) body.get("ruleId");
        String metric = (String) body.get("metric");
        RuleOperator operator = RuleOperator.valueOf(((String) body.get("operator")).toUpperCase());
        double threshold = ((Number) body.get("threshold")).doubleValue();
        String commandName = (String) body.getOrDefault("commandName", "default-command");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) body.getOrDefault("commandPayload", Map.of());
        ruleDefinitionStore.register(new RuleDefinition(ruleId, metric, operator, threshold,
                commandName, payload));
        return Map.of("registered", ruleId);
    }
}
