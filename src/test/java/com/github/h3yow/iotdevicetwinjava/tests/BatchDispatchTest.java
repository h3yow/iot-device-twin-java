package com.github.h3yow.iotdevicetwinjava.tests;

import com.github.h3yow.iotdevicetwinjava.model.BatchDesiredUpdate;
import com.github.h3yow.iotdevicetwinjava.model.BatchDeviceOutcome;
import com.github.h3yow.iotdevicetwinjava.model.BatchDispatchResult;
import com.github.h3yow.iotdevicetwinjava.model.Operator;
import com.github.h3yow.iotdevicetwinjava.model.RejectReason;
import com.github.h3yow.iotdevicetwinjava.model.ShadowUpdate;
import com.github.h3yow.iotdevicetwinjava.model.ShadowUpdateResult;
import com.github.h3yow.iotdevicetwinjava.tests.support.ServiceHarness;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 批量期望配置下发测试。
 *
 * 覆盖：逐台隔离（成功/各类拒绝互不影响、不串版本不串命令）、批次重放结论完全一致且无重复
 * 副作用、同 batchId 换内容 BATCH_ID_CONFLICT、超容量整批拒绝、并发多批次与较大批量、
 * 单台与批量两条路对同一内容重放结论一致。
 */
class BatchDispatchTest {

    private static final Logger LOG = Logger.getLogger(BatchDispatchTest.class.getName());

    private BatchDesiredUpdate req(String batchId, long ts, Map<String, Object> desired,
                                   String... deviceIds) {
        return new BatchDesiredUpdate(batchId, List.of(deviceIds), ts, desired);
    }

    private BatchDeviceOutcome byDevice(BatchDispatchResult r, String deviceId) {
        return r.devices().stream()
                .filter(d -> d.deviceId().equals(deviceId)).findFirst().orElseThrow();
    }

    @Test
    void perDeviceOutcomesAreIsolatedAndReasonsAreDistinguishable() {
        ServiceHarness.caseHeader("批量逐台隔离：生效/未注册互不影响，版本与命令不串台");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline("dev-ok", 1);
        // dev-badtype / dev-unknown 不注册，与 dev-missing 一样按未注册隔离；
        // UNKNOWN_FIELD / TYPE_MISMATCH 的逐台原因由下一个用例单独覆盖。

        // dev-ok 上挂阈值规则，验证生效只在它一台触发命令
        h.addRule("r-hot", "dev-ok", "temperature", Operator.GTE, 30.0, "COOL_ON");

        BatchDesiredUpdate mixed = req("batch-mixed", 100,
                Map.of("mode", "cool", "temperature", 31.0),
                "dev-ok", "dev-badtype", "dev-unknown", "dev-missing");
        BatchDispatchResult result = h.batches.dispatch(mixed);

        assertTrue(result.accepted(), "整批应被受理（逐台成败独立）");
        assertEquals(4, result.devices().size());
        assertEquals(1, result.successCount());
        assertEquals(3, result.rejectedCount());

        BatchDeviceOutcome ok = byDevice(result, "dev-ok");
        assertTrue(ok.accepted());
        assertEquals(1, h.store.getOrThrow("dev-ok").version());
        assertEquals(1, h.store.getOrThrow("dev-ok").deliveredCommands().size(),
                "命令只应在生效的 dev-ok 上产生");

        BatchDeviceOutcome missing = byDevice(result, "dev-missing");
        assertFalse(missing.accepted());
        assertEquals(RejectReason.DEVICE_NOT_REGISTERED, missing.reason());
        assertEquals(RejectReason.DEVICE_NOT_REGISTERED, byDevice(result, "dev-badtype").reason());
        assertEquals(RejectReason.DEVICE_NOT_REGISTERED, byDevice(result, "dev-unknown").reason());

        // 成功设备的结论/版本/命令没有串到失败设备
        assertEquals(0, missing.result().currentVersion(), "未注册设备不存在版本，currentVersion=0");
        assertEquals(1, ok.result().newVersion(), "成功设备结论为 v1，未串到其他设备");

        LOG.info("输入 batchId=batch-mixed 四台设备(3台未注册) 判定依据=逐台独立 注册/schema 校验"
                + " 最终结果=成功1 拒绝3，命令仅在 dev-ok 产生1条，未注册设备无状态可串");
    }

    @Test
    void unknownFieldAndTypeMismatchAreRejectedPerDevice() {
        ServiceHarness.caseHeader("批量中未知字段/类型不符按不同原因逐台拒绝");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline("d-unknown", 1);
        h.registerOnline("d-type", 1);

        BatchDispatchResult unk = h.batches.dispatch(
                req("b-unknown", 10, Map.of("ghost", 1), "d-unknown"));
        BatchDispatchResult typ = h.batches.dispatch(
                req("b-type", 11, Map.of("enabled", "yes"), "d-type"));

        assertEquals(RejectReason.UNKNOWN_FIELD, byDevice(unk, "d-unknown").reason());
        assertEquals(RejectReason.TYPE_MISMATCH, byDevice(typ, "d-type").reason());
        assertEquals(0, h.store.getOrThrow("d-unknown").version());
        assertEquals(0, h.store.getOrThrow("d-type").version());
        LOG.info("判定依据: UNKNOWN_FIELD=["
                + byDevice(unk, "d-unknown").result().fields().get(0).basis() + "] / TYPE_MISMATCH=["
                + byDevice(typ, "d-type").result().fields().get(0).basis() + "] 最终结果=逐台拒绝，版本不推进");
    }

    @Test
    void sameBatchReplayReturnsIdenticalPerDeviceOutcomesWithoutSideEffects() {
        ServiceHarness.caseHeader("同批次同内容重放：逐台结论完全一致，不重复写/不重复下发");
        ServiceHarness h = new ServiceHarness();
        String[] devices = {"rp1", "rp2", "rp3"};
        for (String d : devices) {
            h.registerOnline(d, 1);
        }
        h.addRule("r-hot", "rp2", "temperature", Operator.GTE, 30.0, "COOL_ON");

        BatchDesiredUpdate update = req("batch-replay", 50, Map.of("temperature", 33.0), devices);
        BatchDispatchResult first = h.batches.dispatch(update);
        BatchDispatchResult second = h.batches.dispatch(update);
        BatchDispatchResult third = h.batches.dispatch(update);

        assertTrue(first.accepted());
        assertFalse(first.idempotentReplay());
        assertTrue(second.idempotentReplay(), "同 batchId 同内容重放必须标记为幂等回放");
        assertTrue(third.idempotentReplay());
        for (String d : devices) {
            assertEquals(1, h.store.getOrThrow(d).version(), d + " 重放不推进版本");
        }
        assertEquals(1, h.store.getOrThrow("rp2").deliveredCommands().size(),
                "重放不得重复下发命令");

        assertEquals(first.successCount(), second.successCount());
        assertEquals(first.rejectedCount(), second.rejectedCount());
        for (int i = 0; i < devices.length; i++) {
            BatchDeviceOutcome a = first.devices().get(i);
            BatchDeviceOutcome b = second.devices().get(i);
            BatchDeviceOutcome c = third.devices().get(i);
            assertEquals(a.deviceId(), b.deviceId());
            assertEquals(a.accepted(), b.accepted());
            assertEquals(a.reason(), b.reason());
            assertEquals(a.result().committed(), b.result().committed());
            assertEquals(a.result().newVersion(), b.result().newVersion());
            assertEquals(a.result().fields().size(), b.result().fields().size());
            assertEquals(a.accepted(), c.accepted());
        }
        LOG.info("输入 batchId=batch-replay 重放2次 判定依据=batchId+内容一致 最终结果=三次逐台结论逐项相同，"
                + "版本保持1，rp2 命令始终1条");
    }

    @Test
    void rejectedDevicesConclusionsAreAlsoReplayedStably() {
        ServiceHarness.caseHeader("首次即被拒的设备，重放批次时拒绝结论原样回放");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline("good", 1);
        // bad 保持未注册

        BatchDesiredUpdate update = req("batch-partial", 70, Map.of("mode", "cool"), "good", "bad");
        BatchDispatchResult r1 = h.batches.dispatch(update);
        BatchDispatchResult r2 = h.batches.dispatch(update);

        assertEquals(1, r1.successCount());
        assertEquals(1, r1.rejectedCount());
        assertEquals(RejectReason.DEVICE_NOT_REGISTERED, byDevice(r1, "bad").reason());
        assertEquals(byDevice(r1, "good").result().committed(),
                byDevice(r2, "good").result().committed());
        assertEquals(byDevice(r1, "bad").reason(), byDevice(r2, "bad").reason());
        assertEquals(1, h.store.getOrThrow("good").version(), "重放不重复写成功设备");
        LOG.info("判定依据=批次结论（含未注册拒绝）整体记忆 最终结果=重放成功台仍成功、拒绝台仍同因拒绝");
    }

    @Test
    void sameBatchIdWithDifferentContentIsRejectedWithoutOverwriting() {
        ServiceHarness.caseHeader("同 batchId 换内容：BATCH_ID_CONFLICT，不覆盖、不写一半");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline("c1", 1);
        h.registerOnline("c2", 1);

        BatchDispatchResult first = h.batches.dispatch(
                req("same-batch", 80, Map.of("mode", "cool"), "c1"));
        assertTrue(first.accepted());

        // 换设备清单
        BatchDispatchResult diffDevices = h.batches.dispatch(
                req("same-batch", 80, Map.of("mode", "cool"), "c2"));
        // 换期望内容
        BatchDispatchResult diffContent = h.batches.dispatch(
                req("same-batch", 81, Map.of("mode", "heat"), "c1"));

        assertFalse(diffDevices.accepted());
        assertEquals(RejectReason.BATCH_ID_CONFLICT, diffDevices.reason());
        assertFalse(diffContent.accepted());
        assertEquals(RejectReason.BATCH_ID_CONFLICT, diffContent.reason());

        // 首次结论与状态保持不变，冲突请求没有在 c2 上写入
        assertEquals(0, h.store.getOrThrow("c2").version());
        assertEquals("cool", h.store.getOrThrow("c1").desired().get("mode").value());
        BatchDispatchResult replay = h.batches.dispatch(
                req("same-batch", 80, Map.of("mode", "cool"), "c1"));
        assertTrue(replay.accepted());
        assertEquals(1, replay.successCount());
        assertEquals(1, h.store.getOrThrow("c1").version());
        LOG.info("判定依据=同 batchId 内容签名不一致 最终结果=BATCH_ID_CONFLICT，c1 保持 cool@v1，c2 未被写入");
    }

    @Test
    void batchOverCapacityIsRejectedWholesale() {
        ServiceHarness.caseHeader("超过单批上限：整批 BATCH_TOO_LARGE，一台都不处理");
        ServiceHarness h = new ServiceHarness();
        BatchDispatchServiceWithSmallCap small =
                new BatchDispatchServiceWithSmallCap(h, 3);
        for (int i = 0; i < 4; i++) {
            h.registerOnline("cap" + i, 1);
        }

        BatchDispatchResult over = small.dispatch(
                req("b-too-big", 1, Map.of("mode", "cool"), "cap0", "cap1", "cap2", "cap3"));
        assertFalse(over.accepted());
        assertEquals(RejectReason.BATCH_TOO_LARGE, over.reason());
        assertTrue(over.devices().isEmpty(), "超限批次不处理任何一台");
        for (int i = 0; i < 4; i++) {
            assertEquals(0, h.store.getOrThrow("cap" + i).version(), "不得只处理一部分");
        }
        LOG.info("输入设备数=4 上限=3 判定依据=容量超限 最终结果=整批拒绝，0 台被处理");
    }

    @Test
    void invalidBatchShapeIsRejected() {
        ServiceHarness.caseHeader("空批次/空配置/重复设备/缺 batchId 均整批拒绝");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline("s1", 1);

        assertEquals(RejectReason.BATCH_INVALID,
                h.batches.dispatch(req("b1", 1, Map.of("mode", "cool"))).reason());
        assertEquals(RejectReason.BATCH_INVALID,
                h.batches.dispatch(new BatchDesiredUpdate("b2", List.of("s1"), 1, Map.of())).reason());
        assertEquals(RejectReason.BATCH_INVALID,
                h.batches.dispatch(req("b3", 1, Map.of("mode", "cool"), "s1", "s1")).reason());
        assertEquals(RejectReason.BATCH_INVALID,
                h.batches.dispatch(new BatchDesiredUpdate("  ", List.of("s1"), 1,
                        Map.of("mode", "cool"))).reason());
        assertEquals(0, h.store.getOrThrow("s1").version());
        LOG.info("判定依据=批次结构性校验 最终结果=BATCH_INVALID，无任何设备副作用");
    }

    @Test
    void largeBatchCompletesWithPerDeviceIsolation() throws Exception {
        ServiceHarness.caseHeader("较大批量（300台）逐台并发：全部生效、结论顺序与清单一致");
        ServiceHarness h = new ServiceHarness();
        int n = 300;
        List<String> devices = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String d = "bulk" + i;
            h.registerOnline(d, 1);
            devices.add(d);
        }
        BatchDispatchResult result = h.batches.dispatch(
                new BatchDesiredUpdate("b-bulk", devices, 90, Map.of("mode", "cool")));

        assertTrue(result.accepted());
        assertEquals(n, result.successCount());
        assertEquals(0, result.rejectedCount());
        assertEquals(n, result.devices().size());
        for (int i = 0; i < n; i++) {
            assertEquals(devices.get(i), result.devices().get(i).deviceId(), "结论顺序必须与清单一致");
            assertEquals(1, h.store.getOrThrow(devices.get(i)).version());
        }
        LOG.info("输入设备数=300 判定依据=每台独立锁内合并，线程池并发 最终结果=300/300 生效，顺序与版本确定");
    }

    @Test
    void concurrentDifferentBatchesDoNotStompOnSameDevice() throws Exception {
        ServiceHarness.caseHeader("多批次并发且共享部分设备：同设备串行合并不踩踏、结论确定");
        ServiceHarness h = new ServiceHarness();
        int batches = 12;
        // shared 设备被所有批次同时更新，另有各自独占设备
        h.registerOnline("shared", 1);
        for (int b = 0; b < batches; b++) {
            h.registerOnline("only" + b, 1);
        }

        ExecutorService pool = Executors.newFixedThreadPool(batches);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        List<BatchDispatchResult> results = new ArrayList<>();
        for (int i = 0; i < batches; i++) {
            results.add(null);
        }

        for (int b = 0; b < batches; b++) {
            final int idx = b;
            long ts = 100L + b;
            pool.submit(() -> {
                try {
                    start.await();
                    BatchDesiredUpdate r = new BatchDesiredUpdate("cb" + idx,
                            List.of("shared", "only" + idx), ts, Map.of("mode", "m" + idx));
                    BatchDispatchResult out = h.batches.dispatch(r);
                    results.set(idx, out);
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), "并发批次不得抛异常: " + errors);

        // shared 被 12 个不同 updateId 的更新串行合并：版本恰好 12，无重复/丢失
        assertEquals(batches, h.store.getOrThrow("shared").version(),
                "同一设备的多批次更新必须串行生效、不重复不丢失");
        for (int b = 0; b < batches; b++) {
            BatchDispatchResult r = results.get(b);
            assertTrue(r.accepted());
            assertEquals(2, r.successCount(), "批次 cb" + b + " 两台都应生效");
            assertEquals(1, h.store.getOrThrow("only" + b).version());
        }
        // 每个批次重放结论稳定
        BatchDesiredUpdate replay0 = new BatchDesiredUpdate("cb0",
                List.of("shared", "only0"), 100L, Map.of("mode", "m0"));
        BatchDispatchResult r0again = h.batches.dispatch(replay0);
        assertEquals(2, r0again.successCount());
        assertEquals(batches, h.store.getOrThrow("shared").version(), "重放不额外推进版本");
        LOG.info("输入12个并发批次共享设备shared 判定依据=设备锁串行+逐批次幂等 最终结果=shared 版本=12，"
                + "各独占设备版本=1，重放不回跳");
    }

    @Test
    void singleAndBatchPathsAgreeOnReplayOfSameContent() {
        ServiceHarness.caseHeader("单台与批量两条路：共用同一判定/记忆，各自重放不重复写不重复下发");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline("dev-a", 1);
        h.registerOnline("dev-b", 1);
        h.addRule("r-hot", "dev-a", "temperature", Operator.GTE, 30.0, "COOL_ON");

        // 单台路：dev-a 首次过热触发一次
        ShadowUpdate single = new ShadowUpdate("dev-a", 0, "u-single", 20,
                Map.of("temperature", 31.0));
        ShadowUpdateResult r1 = h.shadows.applyUpdate(single);
        assertTrue(r1.committed());
        ShadowUpdateResult r1replay = h.shadows.applyUpdate(single);
        assertTrue(r1replay.idempotentReplay());
        assertEquals(1, h.store.getOrThrow("dev-a").deliveredCommands().size());

        // 批量路：dev-a 降温（下降沿不触发命令），dev-b 同样收到一份配置并生效
        BatchDispatchResult batch = h.batches.dispatch(
                req("b-x", 21, Map.of("temperature", 20.0), "dev-a", "dev-b"));
        assertTrue(batch.accepted());
        assertTrue(byDevice(batch, "dev-a").accepted());
        assertTrue(byDevice(batch, "dev-b").accepted());

        // dev-a：单台重放最初内容仍是首次结论，版本停在单台+批量两次提交
        assertEquals(2, h.store.getOrThrow("dev-a").version());
        ShadowUpdateResult r1again = h.shadows.applyUpdate(single);
        assertTrue(r1again.idempotentReplay());
        assertEquals(r1.newVersion(), r1again.newVersion());
        assertEquals(1, h.store.getOrThrow("dev-a").deliveredCommands().size(),
                "单台重放不重复下发；批量把温度降到阈值下也不产生新命令");

        // 批量同内容重放：不再写、版本不推进
        BatchDispatchResult batchReplay = h.batches.dispatch(
                req("b-x", 21, Map.of("temperature", 20.0), "dev-a", "dev-b"));
        assertEquals(byDevice(batch, "dev-a").accepted(),
                byDevice(batchReplay, "dev-a").accepted());
        assertEquals(byDevice(batch, "dev-b").reason(), byDevice(batchReplay, "dev-b").reason());
        assertEquals(2, h.store.getOrThrow("dev-a").version());
        assertEquals(1, h.store.getOrThrow("dev-b").version());
        LOG.info("判定依据=单台与批量共用 ShadowService 判定/记忆 最终结果=两条路首次结论均可回放，"
                + "重放均不推进版本、不重复下发命令");
    }

    /** 小容量批量服务，便于容量上限测试。 */
    private static class BatchDispatchServiceWithSmallCap
            extends com.github.h3yow.iotdevicetwinjava.service.BatchDispatchService {
        BatchDispatchServiceWithSmallCap(ServiceHarness h, int cap) {
            super(h.store, h.shadows, cap);
        }
    }
}
