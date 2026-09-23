package com.github.h3yow.iotdevicetwinjava.web;

import com.github.h3yow.iotdevicetwinjava.model.DeviceShadow;
import com.github.h3yow.iotdevicetwinjava.model.ReconnectResult;
import com.github.h3yow.iotdevicetwinjava.model.TelemetryReport;
import com.github.h3yow.iotdevicetwinjava.model.TelemetryResult;
import com.github.h3yow.iotdevicetwinjava.service.DeviceLifecycleService;
import com.github.h3yow.iotdevicetwinjava.service.TelemetryService;
import com.github.h3yow.iotdevicetwinjava.store.DeviceShadowStore;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 设备注册、遥测上报、上下线与重连接口。 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceLifecycleService lifecycle;
    private final TelemetryService telemetry;
    private final DeviceShadowStore store;

    public DeviceController(DeviceLifecycleService lifecycle, TelemetryService telemetry,
                            DeviceShadowStore store) {
        this.lifecycle = lifecycle;
        this.telemetry = telemetry;
        this.store = store;
    }

    @PostMapping("/{deviceId}/register")
    public DeviceShadow register(@PathVariable String deviceId,
                                 @RequestBody(required = false) Map<String, String> body) {
        return lifecycle.register(deviceId, body == null ? null : body.get("deviceType"));
    }

    @GetMapping("/{deviceId}/shadow")
    public DeviceShadow shadow(@PathVariable String deviceId) {
        return store.getOrThrow(deviceId);
    }

    @PostMapping("/{deviceId}/telemetry")
    public TelemetryResult telemetry(@PathVariable String deviceId,
                                     @RequestBody TelemetryReport report) {
        TelemetryReport normalized = report.deviceId() == null
                ? new TelemetryReport(deviceId, report.timestamp(), report.reportId(), report.fields())
                : report;
        return telemetry.report(normalized);
    }

    @PostMapping("/{deviceId}/online")
    public DeviceShadow online(@PathVariable String deviceId, @RequestBody Map<String, Long> body) {
        return lifecycle.markOnline(deviceId, ts(body));
    }

    @PostMapping("/{deviceId}/offline")
    public DeviceShadow offline(@PathVariable String deviceId, @RequestBody Map<String, Long> body) {
        return lifecycle.markOffline(deviceId, ts(body));
    }

    @PostMapping("/{deviceId}/reconnect")
    public ReconnectResult reconnect(@PathVariable String deviceId, @RequestBody Map<String, Long> body) {
        return lifecycle.reconnect(deviceId, ts(body));
    }

    private static long ts(Map<String, Long> body) {
        if (body == null || body.get("timestamp") == null) {
            return System.currentTimeMillis();
        }
        return body.get("timestamp");
    }
}
