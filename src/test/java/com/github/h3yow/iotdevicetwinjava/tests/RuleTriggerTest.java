package com.github.h3yow.iotdevicetwinjava.tests;

import com.github.h3yow.iotdevicetwinjava.model.Command;
import com.github.h3yow.iotdevicetwinjava.model.DeviceStatus;
import com.github.h3yow.iotdevicetwinjava.model.RuleDefinition;
import com.github.h3yow.iotdevicetwinjava.model.RuleOperator;
import com.github.h3yow.iotdevicetwinjava.model.TelemetryReport;
import com.github.h3yow.iotdevicetwinjava.service.FieldSchemaRegistry;
import com.github.h3yow.iotdevicetwinjava.service.RuleEngine;
import com.github.h3yow.iotdevicetwinjava.service.TelemetryService;
import com.github.h3yow.iotdevicetwinjava.store.*;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阈值规则：条件一次持续为真只触发一次；并发更新下不重复下发；
 * 条件恢复为假后重新武装。
 */
class RuleTriggerTest {

    private static final Logger log = LoggerFactory.getLogger(RuleTriggerTest.class);

    private final DeviceStore deviceStore = new DeviceStore();
    private final IdempotencyStore idempotencyStore = new IdempotencyStore();
    private final CommandStore commandStore = new CommandStore();
    private final RuleStateStore ruleStateStore = new RuleStateStore();
    private final RuleDefinitionStore ruleDefinitionStore = new RuleDefinitionStore();
    private final RuleEngine ruleEngine = new RuleEngine(ruleDefinitionStore, ruleStateStore, commandStore);
    private final TelemetryService telemetryService = new TelemetryService(
            deviceStore, idempotencyStore, new FieldSchemaRegistry(), ruleEngine);

    private RuleDefinition coolingRule() {
        return new RuleDefinition("overheat", "temperature", RuleOperator.GT, 30.0,
                "cool-down", Map.of("fan", "on"));
    }

    @Test
    void conditionHoldingTrueFiresOnceRearmsAfterFalse() {
        ruleDefinitionStore.register(coolingRule());
        String device = "dev-r1";
        deviceStore.getOrCreate(device).setStatus(DeviceStatus.ONLINE);

        telemetryService.report(new TelemetryReport(device, 1000, Map.of("temperature", 31.0), null));
        telemetryService.report(new TelemetryReport(device, 2000, Map.of("temperature", 32.0), null));
        telemetryService.report(new TelemetryReport(device, 3000, Map.of("temperature", 33.0), null));

        long fired = commandStore.forDevice(device).stream()
                .filter(c -> c.ruleId().equals("overheat")).count();
        long deliveredAll = commandStore.pending(device).size();
        log.info("[TEST] 输入时间戳 1000/2000/3000 温度 31/32/33 均>30；判定依据: armed 在首次触发后置false；触发命令数={} 未送达={}",
                fired, deliveredAll);
        assertEquals(1, fired, "条件持续为真期间只允许触发一次");

        // 温度回落 -> 重新武装 -> 再超标才第二次触发
        telemetryService.report(new TelemetryReport(device, 4000, Map.of("temperature", 20.0), null));
        telemetryService.report(new TelemetryReport(device, 5000, Map.of("temperature", 35.0), null));
        fired = commandStore.forDevice(device).stream().filter(c -> c.ruleId().equals("overheat")).count();
        log.info("[TEST] 4000温度20(条件变假重新武装) -> 5000温度35再次超标；触发命令总数={}", fired);
        assertEquals(2, fired, "条件经历一次 真->假->真 后应再次触发一次");
    }

    @Test
    void concurrentUpdatesDoNotFireDuplicates() throws InterruptedException {
        ruleDefinitionStore.register(coolingRule());
        String device = "dev-r2";
        deviceStore.getOrCreate(device).setStatus(DeviceStatus.ONLINE);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger applied = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final long ts = 1000 + i; // 乱序/递增混合，全部 > 30
            pool.submit(() -> {
                try {
                    start.await();
                    var out = telemetryService.report(new TelemetryReport(device, ts,
                            Map.of("temperature", 31.0 + (ts % 5)), null));
                    if (out.applied()) {
                        applied.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        long commands = commandStore.forDevice(device).stream().filter(c -> c.ruleId().equals("overheat")).count();
        log.info("[TEST] {} 个线程并发上报(时间戳1000~{},均>30), 生效上报={}；触发结果: overheat命令数={}",
                threads, 1000 + threads - 1, applied.get(), commands);
        assertEquals(1, commands, "并发更新下同一持续条件不得重复下发");

        // 并发结束后最终状态必须收敛：温度=最大时间戳对应值
        var shadow = deviceStore.get(device);
        assertEquals(1000L + threads - 1, shadow.getReportedTimestamp("temperature"));
        log.info("[TEST] 并发后最终状态收敛 temperature={} ts={}",
                shadow.getReportedValue("temperature"), shadow.getReportedTimestamp("temperature"));
    }
}
