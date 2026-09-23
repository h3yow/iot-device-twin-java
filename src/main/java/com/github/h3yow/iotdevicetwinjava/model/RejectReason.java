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
    UPDATE_ID_CONFLICT
}
