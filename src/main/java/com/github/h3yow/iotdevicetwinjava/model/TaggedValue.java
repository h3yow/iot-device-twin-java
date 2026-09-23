package com.github.h3yow.iotdevicetwinjava.model;

/**
 * 影子中单个字段的当前值：携带生效时间戳与写入来源，
 * 使后续“是否过期 / 是否重复”的判定可解释。
 */
public record TaggedValue(Object value, long timestamp, String source) {
    public TaggedValue withValue(Object newValue) {
        return new TaggedValue(newValue, timestamp, source);
    }
}
