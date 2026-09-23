package com.github.h3yow.iotdevicetwinjava.model;

/** 阈值规则比较运算符。 */
public enum Operator {
    GT, GTE, LT, LTE, EQ;

    public boolean test(double actual, double threshold) {
        return switch (this) {
            case GT -> actual > threshold;
            case GTE -> actual >= threshold;
            case LT -> actual < threshold;
            case LTE -> actual <= threshold;
            case EQ -> actual == threshold;
        };
    }
}
