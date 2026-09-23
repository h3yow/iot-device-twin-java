package com.github.h3yow.iotdevicetwinjava.model;

/**
 * 更新被拒绝/未生效的可区分原因。
 * NONE 表示未发生拒绝。
 */
public enum RejectReason {
    NONE,
    UNKNOWN_FIELD,
    STALE_VERSION,
    TYPE_MISMATCH,
    STALE_TIMESTAMP,
    SAME_TIMESTAMP_CONFLICT
}
