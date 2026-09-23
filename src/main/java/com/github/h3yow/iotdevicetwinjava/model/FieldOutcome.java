package com.github.h3yow.iotdevicetwinjava.model;

/**
 * 单个字段的处理结论，用于可解释输出：accepted=false 时带拒绝原因与判定依据。
 */
public record FieldOutcome(String field, boolean accepted, Object incomingValue,
                           Long incomingTs, Object currentValue, Long currentTs,
                           RejectReason reason, String basis) {

    public static FieldOutcome applied(String field, Object value, long ts,
                                       Object previous, Long previousTs) {
        return new FieldOutcome(field, true, value, ts, previous, previousTs, null,
                "incomingTs=" + ts + " 新于 currentTs=" + previousTs + "，覆盖旧值");
    }

    public static FieldOutcome rejected(String field, Object value, Long inTs,
                                        Object current, Long curTs,
                                        RejectReason reason, String basis) {
        return new FieldOutcome(field, false, value, inTs, current, curTs, reason, basis);
    }
}
