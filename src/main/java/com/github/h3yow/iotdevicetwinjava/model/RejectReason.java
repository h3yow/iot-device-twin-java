package com.github.h3yow.iotdevicetwinjava.model;

/**
 * 数据被拒绝的可区分原因。任何拒绝都必须带上该枚举，禁止静默丢弃。
 */
public enum RejectReason {
    /** 遥测字段时间戳早于该字段已保存的时间戳（乱序过期数据）。 */
    STALE_TIMESTAMP,
    /** 完全相同的上报重复到达（同 reportId，或同时间戳同内容），幂等忽略且无副作用。 */
    DUPLICATE_REPORT,
    /** 同一时间戳携带了不同的值，无法判定先后顺序，拒绝以防状态不确定。 */
    SAME_TIMESTAMP_CONFLICT,
    /** 字段不在设备配置模式（schema）允许的字段集合内。 */
    UNKNOWN_FIELD,
    /** 影子更新携带的 baseVersion 落后于当前版本（乐观锁失败）。 */
    STALE_VERSION,
    /** 值的类型与 schema 声明的类型不兼容。 */
    TYPE_MISMATCH,
    /** updateId 与之前一次更新相同，但请求体不一致，疑似错误重放。 */
    UPDATE_ID_CONFLICT,
    /** 批量/单台下发的目标设备未注册。 */
    DEVICE_NOT_REGISTERED,
    /** 批量下发的设备数超过单批容量上限，整批拒绝、不处理任何一台。 */
    BATCH_TOO_LARGE,
    /** 同一 batchId 被用于不同的请求内容（设备清单或期望配置不一致），拒绝覆盖。 */
    BATCH_ID_CONFLICT,
    /** 批量请求自身非法（缺少 batchId、设备清单为空或含重复项等），整批拒绝。 */
    BATCH_INVALID,
    /** 单台设备在执行下发时抛出异常：隔离为该台失败，不影响其他设备。 */
    DEVICE_DISPATCH_FAILED
}
