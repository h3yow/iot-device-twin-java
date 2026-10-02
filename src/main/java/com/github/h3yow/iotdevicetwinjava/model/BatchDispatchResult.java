package com.github.h3yow.iotdevicetwinjava.model;

import java.util.List;

/**
 * 批量下发的整体结论。
 *
 * accepted=false 时为整批级拒绝（超容量/标识冲突/请求非法），devices 为空或仅携带
 * 拒绝说明；accepted=true 时逐台结论见 devices（按请求清单顺序排列）。
 *
 * @param batchId          批次标识
 * @param accepted         整批是否被受理（true 不代表每台成功，逐台看 devices）
 * @param reason           整批拒绝原因；受理时为 null
 * @param idempotentReplay 是否为同 batchId+同内容的幂等回放（回放首次逐台结论，无副作用）
 * @param successCount     生效设备数
 * @param rejectedCount    被拒设备数
 * @param devices          逐台结论，顺序与请求 deviceIds 一致
 * @param message          判定说明（整批拒绝原因或批次汇总信息，便于解释与排障）
 */
public record BatchDispatchResult(String batchId, boolean accepted, RejectReason reason,
                                  boolean idempotentReplay, int successCount, int rejectedCount,
                                  List<BatchDeviceOutcome> devices, String message) {

    public static BatchDispatchResult rejected(String batchId, RejectReason reason, String message) {
        return new BatchDispatchResult(batchId, false, reason, false, 0, 0, List.of(), message);
    }
}
