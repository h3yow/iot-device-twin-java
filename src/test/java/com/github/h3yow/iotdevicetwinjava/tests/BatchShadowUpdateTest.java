package com.github.h3yow.iotdevicetwinjava.tests;

import com.github.h3yow.iotdevicetwinjava.model.BatchShadowUpdate;
import com.github.h3yow.iotdevicetwinjava.model.BatchUpdateResult;
import com.github.h3yow.iotdevicetwinjava.model.DeviceRegistration;
import com.github.h3yow.iotdevicetwinjava.model.DeviceSchema;
import com.github.h3yow.iotdevicetwinjava.model.FieldOutcome;
import com.github.h3yow.iotdevicetwinjava.model.FieldType;
import com.github.h3yow.iotdevicetwinjava.model.Operator;
import com.github.h3yow.iotdevicetwinjava.model.RejectReason;
import com.github.h3yow.iotdevicetwinjava.model.ShadowUpdate;
import com.github.h3yow.iotdevicetwinjava.model.ShadowUpdateResult;
import com.github.h3yow.iotdevicetwinjava.service.BatchShadowService;
import com.github.h3yow.iotdevicetwinjava.tests.support.ServiceHarness;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 批量期望配置下发测试：逐台隔离与可区分原因、批次幂等重放、batchId 冲突、
 * 容量上限、并发与较大批量、部分失败不串台、单台/批量两路重放一致。
 */
class BatchShadowUpdateTest {

    private static final Logger LOG = Logger.getLogger(BatchShadowUpdateTest.class.getName());

    private static ShadowUpdateResult deviceResult(BatchUpdateResult r, String deviceId) {
        return r.deviceResults().stream()
                .filter(d -> d.deviceId().equals(deviceId)).findFirst().orElseThrow();
    }

    private static RejectReason firstReason(ShadowUpdateResult r) {
        return r.fields().stream().map(FieldOutcome::reason)
                .filter(java.util.Objects::nonNull).findFirst().orElseThrow();
    }

    @Test
    void perDeviceIsolationWithDistinguishableReasons() {
        ServiceHarness.caseHeader("批量逐台隔离：同批内生效/版本过期/字段不认识/设备未注册互不干扰");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline("dev-ok1", 1);
        h.registerOnline("dev-ok2", 1);
        h.registerOnline("dev-stale", 1);
        // dev-stale 先推进到 v1，批量里仍用 baseVersion=0 -> 该台 STALE_VERSION
        h.shadows.applyUpdate(new ShadowUpdate("dev-stale", 0, "pre-1", 5, Map.of("mode", "heat")));
        // dev-custom 的 schema 不含 mode 字段 -> 该台 UNKNOWN_FIELD
        h.store.register(new DeviceRegistration("dev-custom", "custom-type",
                new DeviceSchema("custom-type", 1, Map.of("temperature", FieldType.NUMBER))));
        // dev-missing 不注册 -> 该台 DEVICE_NOT_FOUND

        BatchShadowUpdate batch = new BatchShadowUpdate("b-iso", 0, 100,
                Map.of("mode", "cool", "temperature", 25.0),
                List.of("dev-ok1", "dev-stale", "dev-custom", "dev-missing", "dev-ok2"));
        BatchUpdateResult r = h.batchShadows.applyBatch(batch);

        assertFalse(r.batchRejected());
        assertEquals(5, r.deviceResults().size());
        assertEquals(List.of("dev-custom", "dev-missing", "dev-ok1", "dev-ok2", "dev-stale"),
                r.deviceResults().stream().map(ShadowUpdateResult::deviceId).toList(),
                "逐台结论按 deviceId 排序");

        assertTrue(deviceResult(r, "dev-ok1").committed());
        assertTrue(deviceResult(r, "dev-ok2").committed());
        assertEquals(1, deviceResult(r, "dev-ok1").newVersion());
        assertEquals(1, deviceResult(r, "dev-ok2").newVersion());

        ShadowUpdateResult stale = deviceResult(r, "dev-stale");
        assertFalse(stale.committed());
        assertEquals(RejectReason.STALE_VERSION, firstReason(stale));
        assertEquals(1, stale.currentVersion(), "拒绝结论带该台自己的当前版本，不串台");

        assertEquals(RejectReason.UNKNOWN_FIELD, firstReason(deviceResult(r, "dev-custom")));
        assertEquals(RejectReason.DEVICE_NOT_FOUND, firstReason(deviceResult(r, "dev-missing")));

        // 落库状态核对：成功的生效，失败的原样
        assertEquals("cool", h.store.getOrThrow("dev-ok1").desired().get("mode").value());
        assertEquals(1, h.store.getOrThrow("dev-stale").version());
        assertEquals("heat", h.store.getOrThrow("dev-stale").desired().get("mode").value(),
                "dev-stale 整台拒绝，不得被同批其他设备的成功写入");
        assertEquals(0, h.store.getOrThrow("dev-custom").version());
        LOG.info("批次=b-iso 逐台结论: 生效2台 / STALE_VERSION / UNKNOWN_FIELD / DEVICE_NOT_FOUND，"
                + "判定依据互相独立");
    }

    @Test
    void batchReplaySameContentGivesIdenticalConclusions() {
        ServiceHarness.caseHeader("同 batchId 同内容重发：逐台结论与首次一致，不重复写状态/下发命令");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline("dev-a", 1);
        h.registerOnline("dev-b", 1);
        h.addRule("r-hot", "dev-a", "temperature", Operator.GTE, 30.0, "COOL_ON");
        // dev-missing 不注册：首次即部分失败，重放也必须保留该台的拒绝结论

        BatchShadowUpdate batch = new BatchShadowUpdate("b-replay", 0, 100,
                Map.of("temperature", 31.0), List.of("dev-a", "dev-b", "dev-missing"));
        BatchUpdateResult first = h.batchShadows.applyBatch(batch);
        BatchUpdateResult replay = h.batchShadows.applyBatch(batch);
        BatchUpdateResult replay2 = h.batchShadows.applyBatch(batch);

        assertEquals(2, first.committedCount());
        assertEquals(RejectReason.DEVICE_NOT_FOUND, firstReason(deviceResult(first, "dev-missing")));

        assertTrue(replay.idempotentReplay() && replay2.idempotentReplay());
        assertEquals(first.deviceResults(), replay.deviceResults(), "重放逐台结论与首次完全一致");
        assertEquals(replay.deviceResults(), replay2.deviceResults());
        assertEquals(1, h.store.getOrThrow("dev-a").version(), "重放不推进版本");
        assertEquals(1, h.store.getOrThrow("dev-a").deliveredCommands().size(),
                "重放不重复下发命令");
        LOG.info("批次=b-replay 判定依据=batchId+内容签名一致 触发结果=重放首次结论(含 DEVICE_NOT_FOUND 台)，"
                + "版本保持1 命令保持1条");
    }

    @Test
    void sameBatchIdWithDifferentContentIsRejected() {
        ServiceHarness.caseHeader("同 batchId 换内容：BATCH_ID_CONFLICT 整批拒绝，不覆盖不写一半");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline("dev-a", 1);
        h.registerOnline("dev-b", 1);

        BatchUpdateResult first = h.batchShadows.applyBatch(
                new BatchShadowUpdate("b-conflict", 0, 100, Map.of("mode", "cool"),
                        List.of("dev-a", "dev-b")));
        assertEquals(2, first.committedCount());

        // 换 desired 内容
        BatchUpdateResult conflict = h.batchShadows.applyBatch(
                new BatchShadowUpdate("b-conflict", 0, 101, Map.of("mode", "heat"),
                        List.of("dev-a", "dev-b")));
        assertTrue(conflict.batchRejected());
        assertEquals(RejectReason.BATCH_ID_CONFLICT, conflict.rejectReason());
        assertTrue(conflict.deviceResults().isEmpty());

        // 只换设备清单也算不同内容
        BatchUpdateResult conflict2 = h.batchShadows.applyBatch(
                new BatchShadowUpdate("b-conflict", 0, 100, Map.of("mode", "cool"),
                        List.of("dev-a")));
        assertTrue(conflict2.batchRejected());
        assertEquals(RejectReason.BATCH_ID_CONFLICT, conflict2.rejectReason());

        assertEquals(1, h.store.getOrThrow("dev-a").version(), "冲突请求不得写任何设备");
        assertEquals("cool", h.store.getOrThrow("dev-a").desired().get("mode").value());

        // 首次结论仍可重放，未被冲突请求覆盖
        BatchUpdateResult replay = h.batchShadows.applyBatch(
                new BatchShadowUpdate("b-conflict", 0, 100, Map.of("mode", "cool"),
                        List.of("dev-a", "dev-b")));
        assertTrue(replay.idempotentReplay());
        assertEquals(first.deviceResults(), replay.deviceResults());
        LOG.info("批次=b-conflict 判定依据=batchId 已用于其他内容 触发结果=BATCH_ID_CONFLICT，"
                + "已存结论与设备状态不变");
    }

    @Test
    void oversizedOrEmptyBatchIsRejectedWithoutPartialProcessing() {
        ServiceHarness.caseHeader("超上限/空清单整批拒绝，不处理子集；校验失败不占用 batchId");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline("dev-a", 1);
        h.registerOnline("dev-b", 1);

        List<String> tooMany = IntStream.range(0, BatchShadowService.MAX_BATCH_SIZE + 1)
                .mapToObj(i -> "dev-x" + i).toList();
        BatchUpdateResult tooLarge = h.batchShadows.applyBatch(
                new BatchShadowUpdate("b-big", 0, 100, Map.of("mode", "cool"), tooMany));
        assertTrue(tooLarge.batchRejected());
        assertEquals(RejectReason.BATCH_TOO_LARGE, tooLarge.rejectReason());
        assertTrue(tooLarge.deviceResults().isEmpty());
        assertEquals(0, h.store.getOrThrow("dev-a").version(), "超限批次不得处理任何设备");

        BatchUpdateResult empty = h.batchShadows.applyBatch(
                new BatchShadowUpdate("b-empty", 0, 100, Map.of("mode", "cool"), List.of()));
        assertTrue(empty.batchRejected());
        assertEquals(RejectReason.EMPTY_BATCH, empty.rejectReason());

        // 校验失败的请求不占用 batchId：同标识换成合法规模可正常执行
        BatchUpdateResult retried = h.batchShadows.applyBatch(
                new BatchShadowUpdate("b-big", 0, 100, Map.of("mode", "cool"),
                        List.of("dev-a", "dev-b")));
        assertFalse(retried.batchRejected());
        assertEquals(2, retried.committedCount());
        LOG.info("批次=b-big 判定依据=设备数501>上限500 触发结果=BATCH_TOO_LARGE 整批拒绝；"
                + "同标识合法规模重发正常生效");
    }

    @Test
    void concurrentBatchesAndLargeBatchAreDeterministic() throws Exception {
        ServiceHarness.caseHeader("较大批量 + 并发批次：同设备串行不踩踏，结论确定");
        ServiceHarness h = new ServiceHarness();
        int deviceCount = 300;
        List<String> devices = IntStream.range(0, deviceCount).mapToObj(i -> "dev-" + i).toList();
        devices.forEach(d -> h.registerOnline(d, 1));
        h.addRule("r-all", null, "temperature", Operator.GTE, 30.0, "COOL_ON");

        // ---- 单批 300 台 ----
        BatchUpdateResult big = h.batchShadows.applyBatch(
                new BatchShadowUpdate("b-300", 0, 100, Map.of("temperature", 31.0), devices));
        assertFalse(big.batchRejected());
        assertEquals(deviceCount, big.committedCount());
        devices.forEach(d -> assertEquals(1, h.store.getOrThrow(d).version()));
        LOG.info("批次=b-300 触发结果=300台全部生效，版本各推进到1");

        // ---- 同内容不同 batchId 并发重试：内容级重放，版本/命令不重复 ----
        int retryBatches = 6;
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<BatchUpdateResult> retryResults = new ConcurrentLinkedQueue<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < retryBatches; i++) {
            String batchId = "b-retry-" + i;
            Thread t = new Thread(() -> {
                await(start);
                retryResults.add(h.batchShadows.applyBatch(new BatchShadowUpdate(
                        batchId, 0, 100, Map.of("temperature", 31.0), devices)));
            });
            threads.add(t);
            t.start();
        }
        start.countDown();
        for (Thread t : threads) {
            t.join(TimeUnit.SECONDS.toMillis(60));
        }
        assertEquals(retryBatches, retryResults.size());
        for (BatchUpdateResult r : retryResults) {
            assertFalse(r.batchRejected());
            assertEquals(deviceCount, r.committedCount(),
                    "同内容重试批次对每台都应得到与首次一致的生效结论");
        }
        devices.forEach(d -> {
            assertEquals(1, h.store.getOrThrow(d).version(), "并发重试不得重复推进版本: " + d);
            assertEquals(1, h.store.getOrThrow(d).deliveredCommands().size(),
                    "并发重试不得重复下发命令: " + d);
        });

        // ---- 同 batchId 并发重发（网关超时重试）：只执行一次，结论一致 ----
        CountDownLatch start2 = new CountDownLatch(1);
        ConcurrentLinkedQueue<BatchUpdateResult> sameIdResults = new ConcurrentLinkedQueue<>();
        List<Thread> threads2 = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread t = new Thread(() -> {
                await(start2);
                sameIdResults.add(h.batchShadows.applyBatch(new BatchShadowUpdate(
                        "b-same", 1, 200, Map.of("mode", "cool"), devices)));
            });
            threads2.add(t);
            t.start();
        }
        start2.countDown();
        for (Thread t : threads2) {
            t.join(TimeUnit.SECONDS.toMillis(60));
        }
        assertEquals(8, sameIdResults.size());
        BatchUpdateResult any = sameIdResults.peek();
        sameIdResults.forEach(r -> assertEquals(any.deviceResults(), r.deviceResults(),
                "同 batchId 并发重发，所有调用方拿到同一份逐台结论"));
        devices.forEach(d -> assertEquals(2, h.store.getOrThrow(d).version(),
                "同 batchId 并发重发只生效一次: " + d));

        // ---- 同设备上两个不同内容批次并发：一台一台串行，谁先到谁生效 ----
        CountDownLatch start3 = new CountDownLatch(1);
        ConcurrentLinkedQueue<BatchUpdateResult> raceResults = new ConcurrentLinkedQueue<>();
        Thread t1 = new Thread(() -> {
            await(start3);
            raceResults.add(h.batchShadows.applyBatch(new BatchShadowUpdate(
                    "b-race-1", 2, 300, Map.of("mode", "heat"), devices)));
        });
        Thread t2 = new Thread(() -> {
            await(start3);
            raceResults.add(h.batchShadows.applyBatch(new BatchShadowUpdate(
                    "b-race-2", 2, 300, Map.of("mode", "dry"), devices)));
        });
        t1.start();
        t2.start();
        start3.countDown();
        t1.join(TimeUnit.SECONDS.toMillis(60));
        t2.join(TimeUnit.SECONDS.toMillis(60));
        assertEquals(2, raceResults.size());
        for (String d : devices) {
            assertEquals(3, h.store.getOrThrow(d).version(),
                    "两个批次同设备串行，各生效一次，版本单调到3: " + d);
        }
        LOG.info("并发场景 触发结果=300台x多批次结论确定：重试不重复生效，竞争批次各生效一次、无踩踏");
    }

    @Test
    void singleAndBatchPathsReplaySameContentConsistently() {
        ServiceHarness.caseHeader("单台接口与批量接口互放同一份内容：结论一致，不重复生效");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline("dev-x", 1);
        h.registerOnline("dev-y", 1);
        h.addRule("r-x", "dev-x", "temperature", Operator.GTE, 30.0, "COOL_ON");
        h.addRule("r-y", "dev-y", "temperature", Operator.GTE, 30.0, "COOL_ON");

        // 单台 -> 批量重放同内容
        ShadowUpdateResult single = h.shadows.applyUpdate(
                new ShadowUpdate("dev-x", 0, "u-single", 50, Map.of("temperature", 33.0)));
        assertTrue(single.committed());
        BatchUpdateResult batchReplay = h.batchShadows.applyBatch(
                new BatchShadowUpdate("b-cross-1", 0, 50, Map.of("temperature", 33.0),
                        List.of("dev-x")));
        ShadowUpdateResult viaBatch = deviceResult(batchReplay, "dev-x");
        assertTrue(viaBatch.idempotentReplay(), "批量重放单台已处理内容应命中内容级幂等");
        assertEquals(single.committed(), viaBatch.committed());
        assertEquals(single.newVersion(), viaBatch.newVersion());
        assertEquals(single.fields(), viaBatch.fields(), "两条路径逐字段结论一致");
        assertEquals(1, h.store.getOrThrow("dev-x").version());
        assertEquals(1, h.store.getOrThrow("dev-x").deliveredCommands().size());

        // 批量 -> 单台重放同内容
        BatchUpdateResult batch = h.batchShadows.applyBatch(
                new BatchShadowUpdate("b-cross-2", 0, 60, Map.of("temperature", 35.0),
                        List.of("dev-y")));
        assertTrue(deviceResult(batch, "dev-y").committed());
        ShadowUpdateResult singleReplay = h.shadows.applyUpdate(
                new ShadowUpdate("dev-y", 0, "u-single-2", 60, Map.of("temperature", 35.0)));
        assertTrue(singleReplay.idempotentReplay(), "单台重放批量已处理内容应命中内容级幂等");
        assertEquals(deviceResult(batch, "dev-y").newVersion(), singleReplay.newVersion());
        assertEquals(1, h.store.getOrThrow("dev-y").version());
        assertEquals(1, h.store.getOrThrow("dev-y").deliveredCommands().size());
        LOG.info("判定依据=内容签名一致(跨单台/批量路径) 触发结果=重放首次结论，版本与命令均不重复");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
