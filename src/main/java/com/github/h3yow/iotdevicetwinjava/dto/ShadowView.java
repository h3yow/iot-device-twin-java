package com.github.h3yow.iotdevicetwinjava.dto;

import com.github.h3yow.iotdevicetwinjava.model.DeviceShadow;
import com.github.h3yow.iotdevicetwinjava.model.DeviceStatus;

import java.util.Map;

/** 影子只读视图（REST 返回）。 */
public record ShadowView(String deviceId,
                         int version,
                         DeviceStatus status,
                         long lastSeenTimestamp,
                         Map<String, Object> reported,
                         Map<String, Long> reportedTimestamps,
                         Map<String, Object> desired) {

    public static ShadowView of(DeviceShadow s) {
        return new ShadowView(s.getDeviceId(), s.getVersion(), s.getStatus(),
                s.getLastSeenTimestamp(), s.getReported(), s.getReportedTimestamps(), s.getDesired());
    }
}
