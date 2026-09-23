package com.github.h3yow.iotdevicetwinjava.model;

/**
 * 一次上报/更新的结论。
 *
 * @param accepted    是否通过校验（幂等命中的重复请求也视为通过）
 * @param applied     是否实际改动了状态（重复/过期数据不改动）
 * @param reason      未生效或被拒绝的可区分原因
 * @param version     结论作出时的影子版本
 * @param detail      判定依据的可读说明
 * @param clientToken 回显的幂等令牌
 */
public record UpdateOutcome(boolean accepted,
                            boolean applied,
                            RejectReason reason,
                            int version,
                            String detail,
                            String clientToken) {
}
