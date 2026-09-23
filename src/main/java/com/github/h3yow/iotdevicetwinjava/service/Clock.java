package com.github.h3yow.iotdevicetwinjava.service;

/** 统一时钟，测试可替换为固定时间，保证结论可复现。 */
@org.springframework.stereotype.Component
public class Clock {
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
