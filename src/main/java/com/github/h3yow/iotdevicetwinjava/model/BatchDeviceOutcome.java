package com.github.h3yow.iotdevicetwinjava.model;

/**
 * 批量下发中单台设备的结论。逐台独立判定、互不影响：
 * 某台被拒不改变其他设备的成功结果，判定依据/版本/命令也不串台。
 *
 * @param deviceId 设备
 * @param accepted 该台是否生效（单台影子更新 committed）
 * @param reason   整台级别的拒绝原因；字段级原因见 result.fields()
 * @param result   该台影子更新的完整结论（含版本、逐字段判定、幂等回放标记）
 */
public record BatchDeviceOutcome(String deviceId, boolean accepted, RejectReason reason,
                                 ShadowUpdateResult result) {
}
