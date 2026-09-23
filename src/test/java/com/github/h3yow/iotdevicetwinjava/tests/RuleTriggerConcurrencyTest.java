package com.github.h3yow.iotdevicetwinjava.tests;

import com.github.h3yow.iotdevicetwinjava.model.TelemetryReport;
import com.github.h3yow.iotdevicetwinjava.tests.support.ServiceHarness;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 规则触发并发测试：同一条件被多线程同时满足时只下发一次；
 * 条件持续满足（含多版本持续满足）不重复下发，直到条件解除后再次进入才重新触发。
 */
class RuleTriggerConcurrencyTest {

    private static final Logger LOG = Logger.getLogger(RuleTriggerConcurrencyTest.class.getName());

    @Test
    void concurrentIdenticalReportsFireCommandExactlyOnce() throws Exception {
        ServiceHarness.caseHeader("并发相同上报：命令只下发一次");
        ServiceHarness h = new ServiceHarness();
        String device = "dev-C";
        h.registerOnline(device, 1);
        h.addRule("r-hot", device, "temperature",
                com.github.h3yow.iotdevicetwinjava.model.Operator.GT, 28.0, "COOL_ON");

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger firedObserved = new AtomicInteger();

        // 每个线程使用自己的 reportId、但相同时间戳与值；
        // 按 SAME_TIMESTAMP_CONFLICT 语义它们不可能同时写入，
        // 因此这里改为不同时间戳（同一上升沿窗口）验证边沿+去重的双重保护。
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    var r = h.telemetry.report(new TelemetryReport(device, 100 + idx,
                            "concurrent-" + idx, Map.of("temperature", 30.0 + idx * 0.0001)));
                    if (!r.idempotentReplay()) {
                        firedObserved.addAndGet(r.commands().size());
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        long delivered = h.store.getOrThrow(device).deliveredCommands().size();
        long dedupKeys = h.store.getOrThrow(device).deliveredDedupKeys().size();
        LOG.info("输入时间戳=100..115(16 线程并发) 判定依据=设备锁串行+边沿触发+dedupKey 去重"
                + " 触发结果=实际下发命令 " + delivered + " 条");
        assertEquals(1, delivered, "同一上升沿条件即使被 16 个并发更新满足，也只能下发一次");
        assertEquals(1, dedupKeys);
        assertEquals(1, firedObserved.get());
    }

    @Test
    void conditionHoldingAcrossVersionsDoesNotRefire() {
        ServiceHarness.caseHeader("条件持续满足不重复触发，解除后再满足才重新触发");
        ServiceHarness h = new ServiceHarness();
        String device = "dev-D";
        h.registerOnline(device, 1);
        h.addRule("r-hot", device, "temperature",
                com.github.h3yow.iotdevicetwinjava.model.Operator.GTE, 30.0, "COOL_ON");

        h.telemetry.report(new TelemetryReport(device, 10, "t1", Map.of("temperature", 31.0)));
        h.telemetry.report(new TelemetryReport(device, 11, "t2", Map.of("temperature", 32.0)));
        h.telemetry.report(new TelemetryReport(device, 12, "t3", Map.of("humidity", 50.0)));
        assertEquals(1, h.store.getOrThrow(device).deliveredCommands().size(),
                "条件持续成立期间的后续更新不得重复下发");

        // 条件解除（下降沿）
        h.telemetry.report(new TelemetryReport(device, 13, "t4", Map.of("temperature", 20.0)));
        // 再次满足（新的上升沿）：允许再下发一条，且 dedupKey 随版本不同而不同
        h.telemetry.report(new TelemetryReport(device, 14, "t5", Map.of("temperature", 33.0)));
        assertEquals(2, h.store.getOrThrow(device).deliveredCommands().size());
        assertEquals(2, h.store.getOrThrow(device).deliveredDedupKeys().size());
        LOG.info("输入时间戳=10..14 判定依据=上升沿触发一次->持续不触发->下降沿->再上升沿再触发"
                + " 触发结果=共 2 条命令，dedupKey 随版本区分");
    }

    @Test
    void parallelDifferentDevicesDoNotBlockEachOther() throws Exception {
        ServiceHarness.caseHeader("不同设备并发互不阻塞且各自触发一次");
        ServiceHarness h = new ServiceHarness();
        int n = 8;
        for (int i = 0; i < n; i++) {
            String device = "dev-" + i;
            h.registerOnline(device, 1);
            h.addRule("r-" + i, device, "temperature",
                    com.github.h3yow.iotdevicetwinjava.model.Operator.GT, 28.0, "COOL_ON");
        }
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    h.telemetry.report(new TelemetryReport("dev-" + idx, 50, "x",
                            Map.of("temperature", 35.0)));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        for (int i = 0; i < n; i++) {
            assertEquals(1, h.store.getOrThrow("dev-" + i).deliveredCommands().size());
        }
        LOG.info("判定依据=每设备独立锁 触发结果=" + n + " 台设备各下发 1 条");
    }
}
