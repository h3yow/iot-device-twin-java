package com.github.h3yow.iotdevicetwinjava.model;

import java.util.List;
import java.util.Map;

/**
 * 批量期望配置下发请求：把同一份 desired 配置发给一批设备。
 *
 * @param batchId   批次幂等键；同 batchId + 同内容重放必须返回完全一致的逐台结论，
 *                  同 batchId 换内容以 BATCH_ID_CONFLICT 拒绝
 * @param deviceIds 目标设备清单（按清单顺序返回逐台结论）
 * @param timestamp 配置时间戳，随每台设备的字段值保存（判定依据的一部分）
 * @param desired   所有设备共用的同一份期望配置
 */
public record BatchDesiredUpdate(String batchId, List<String> deviceIds, long timestamp,
                                 Map<String, Object> desired) {
}
