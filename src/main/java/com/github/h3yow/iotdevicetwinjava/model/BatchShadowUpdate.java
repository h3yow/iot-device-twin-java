package com.github.h3yow.iotdevicetwinjava.model;

import java.util.List;
import java.util.Map;

/**
 * 批量期望配置下发请求：把同一份 desired 发给一批设备。
 *
 * @param batchId    批次标识（幂等键）。同 batchId + 同内容重发返回首次逐台结论；
 *                   同 batchId 不同内容以 BATCH_ID_CONFLICT 整批拒绝
 * @param baseVersion 对清单中每台设备统一使用的期望基准版本；
 *                    某台设备当前版本不等于它时，该台以 STALE_VERSION 拒绝（不影响其他设备）
 * @param timestamp  请求时间戳，写入每台设备 desired 字段的 TaggedValue
 * @param desired    同一份期望配置，逐台按各自 schema 校验
 * @param deviceIds  目标设备清单；超过单批上限整批拒绝（BATCH_TOO_LARGE），不处理子集
 */
public record BatchShadowUpdate(String batchId, long baseVersion, long timestamp,
                                Map<String, Object> desired, List<String> deviceIds) {
}
