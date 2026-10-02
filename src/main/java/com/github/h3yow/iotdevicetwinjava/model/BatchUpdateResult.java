package com.github.h3yow.iotdevicetwinjava.model;

import java.util.List;

/**
 * 批量期望配置下发结论。
 *
 * batchRejected=true 表示批次级拒绝（BATCH_TOO_LARGE / EMPTY_BATCH / BATCH_ID_CONFLICT），
 * 此时 deviceResults 为空、任何设备都没有被处理；
 * batchRejected=false 时 deviceResults 按 deviceId 排序逐台给出结论，
 * 单台失败不影响其他台已成功的结果。
 *
 * @param idempotentReplay 本次为同 batchId + 同内容的重放，返回的是首次结论
 */
public record BatchUpdateResult(String batchId, boolean batchRejected,
                                RejectReason rejectReason, String basis,
                                boolean idempotentReplay,
                                List<ShadowUpdateResult> deviceResults) {

    public static BatchUpdateResult rejected(String batchId, RejectReason reason, String basis) {
        return new BatchUpdateResult(batchId, true, reason, basis, false, List.of());
    }

    /** 已提交（生效）的设备数。 */
    public long committedCount() {
        return deviceResults.stream().filter(ShadowUpdateResult::committed).count();
    }

    /** 被拒绝（未生效）的设备数。 */
    public long rejectedCount() {
        return deviceResults.size() - committedCount();
    }
}
