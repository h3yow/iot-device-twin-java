package com.github.h3yow.iotdevicetwinjava.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个设备的本地影子状态。
 *
 * 所有变更方法都必须在持有 {@code DeviceStore.lockFor(deviceId)} 的前提下调用，
 * 从而保证“读-改-写 + 规则判定”对同一设备串行化，并发结论确定。
 *
 * 收敛语义：
 * - reported 按字段做 last-writer-wins：仅当该字段的新时间戳严格更大才覆盖；
 * - desired 为版本化合并：补丁整体校验通过后版本号 +1；
 * - 重复/过期数据不改动任何状态，也不产生副作用。
 */
public final class DeviceShadow {

    private final String deviceId;
    private int version;
    private DeviceStatus status;
    private long lastSeenTimestamp;

    /** 指标名 -> 最新值。 */
    private final Map<String, Object> reported = new LinkedHashMap<>();
    /** 指标名 -> 该值最近生效的上报时间戳。 */
    private final Map<String, Long> reportedTimestamps = new LinkedHashMap<>();
    /** 期望字段（版本化合并后全量）。 */
    private final Map<String, Object> desired = new LinkedHashMap<>();

    public DeviceShadow(String deviceId) {
        this.deviceId = deviceId;
        this.version = 0;
        this.status = DeviceStatus.OFFLINE;
        this.lastSeenTimestamp = -1L;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public int getVersion() {
        return version;
    }

    public DeviceStatus getStatus() {
        return status;
    }

    public long getLastSeenTimestamp() {
        return lastSeenTimestamp;
    }

    /** 只读快照：指标名 -> 最新值。 */
    public Map<String, Object> getReported() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(reported));
    }

    /** 只读快照：指标名 -> 该指标最近生效的上报时间戳。 */
    public Map<String, Long> getReportedTimestamps() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(reportedTimestamps));
    }

    /** 只读快照：期望字段。 */
    public Map<String, Object> getDesired() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(desired));
    }

    // ---- 以下方法仅允许在设备锁内调用 ----

    public Object getReportedValue(String metric) {
        return reported.get(metric);
    }

    public Long getReportedTimestamp(String metric) {
        return reportedTimestamps.get(metric);
    }

    /**
     * 写入单个指标。调用方需先完成字段与类型校验。
     *
     * @return 1 表示覆盖了较旧值；0 表示时间戳相等且值相同（重复）；
     *         -1 表示时间戳更旧（过期，跳过）；-2 表示同时间戳但值冲突。
     */
    public int putReported(String metric, Object value, long timestamp) {
        Long oldTs = reportedTimestamps.get(metric);
        if (oldTs == null) {
            reported.put(metric, value);
            reportedTimestamps.put(metric, timestamp);
            return 1;
        }
        if (timestamp > oldTs) {
            reported.put(metric, value);
            reportedTimestamps.put(metric, timestamp);
            return 1;
        }
        if (timestamp == oldTs) {
            Object oldValue = reported.get(metric);
            return java.util.Objects.equals(oldValue, value) ? 0 : -2;
        }
        return -1;
    }

    public void touch(long timestamp) {
        if (timestamp > lastSeenTimestamp) {
            lastSeenTimestamp = timestamp;
        }
    }

    /** 版本化合并期望字段（整批补丁通过校验后调用），版本号 +1。 */
    public int mergeDesired(Map<String, Object> fields) {
        desired.putAll(fields);
        version += 1;
        return version;
    }

    public void setStatus(DeviceStatus status) {
        this.status = status;
    }
}
