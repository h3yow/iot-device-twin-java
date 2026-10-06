package com.github.h3yow.iotdevicetwinjava.tests;

import com.github.h3yow.iotdevicetwinjava.model.BatchDesiredUpdate;
import com.github.h3yow.iotdevicetwinjava.model.BatchDeviceOutcome;
import com.github.h3yow.iotdevicetwinjava.model.BatchDispatchResult;
import com.github.h3yow.iotdevicetwinjava.model.FieldOutcome;
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
 * 单台与批量两条下发路径的跨路径幂等一致性测试。
 *
 * 核心语义：同一台设备上，内容签名（时间戳 + 期望内容）相同的下发，无论走
 * 单台 {@code POST /api/shadows/update} 还是批量 {@code POST /api/batch/desired}，
 * 也无论 updateId/batchId 是否相同，重复的那一次都必须认出是重复：回放首次结论
 * （idempotentReplay=true），不重复写状态、不推进版本、不重复下发命令。
 * 首次被拒（未注册/未知字段/类型不符）同样跨路径回放同一拒绝结论。
 */
class CrossPathIdempotencyTest {

    private static final Logger LOG = Logger.getLogger(CrossPathIdempotencyTest.class.getName());

    private BatchDeviceOutcome byDevice(BatchDispatchResult r, String deviceId) {
        return r.devices().stream()
                .filter(d -> d.deviceId().equals(deviceId)).findFirst().orElseThrow();
    }

    private RejectReason firstReason(ShadowUpdateResult r) {
        return r.fields().stream().map(FieldOutcome::reason)
                .filter(java.util.Objects::nonNull).findFirst().orElseThrow();
    }

    @Test
    void singleThenBatchSameContentReplaysFirstConclusion() {
        ServiceHarness.caseHeader("先单台后批量：同内容被批量认出重复，回放首次结论不重复下发");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline("dev-a", 1);
        h.registerOnline("dev-b", 1);
        h.addRule("r-hot", "dev-a", "temperature", Operator.GTE, 30.0, "COOL_ON");

        // 单台路首次下发：生效并触发 1 条命令
        ShadowUpdateResult single = h.shadows.applyUpdate(
                new ShadowUpdate("dev-a", 0, "u-single-1", 20, Map.of("temperature", 31.0)));
        assertTrue(single.committed());
        assertEquals(1, single.newVersion());
        assertEquals(1, h.store.getOrThrow("dev-a").deliveredCommands().size());

        // 批量路原样再发同一份内容（同时间戳同 desired），dev-b 为对照（全新内容）
        BatchDispatchResult batch = h.batches.dispatch(new BatchDesiredUpdate(
                "b-after-single", List.of("dev-a", "dev-b"), 20, Map.of("temperature", 31.0)));

        BatchDeviceOutcome a = byDevice(batch, "dev-a");
        assertTrue(a.accepted(), "重复下发仍回放首次的成功结论");
        assertTrue(a.result().idempotentReplay(), "批量路必须认出这是单台已处理过的同一份内容");
        assertEquals(single.newVersion(), a.result().newVersion(), "回放首次结论的版本号");
        assertEquals(1, h.store.getOrThrow("dev-a").version(), "重复下发不得再次推进版本");
        assertEquals(1, h.store.getOrThrow("dev-a").deliveredCommands().size(),
                "重复下发不得重复下发命令");

        BatchDeviceOutcome b = byDevice(batch, "dev-b");
        assertTrue(b.accepted());
        assertFalse(b.result().idempotentReplay(), "dev-b 是全新内容，应正常生效");
        assertEquals(1, h.store.getOrThrow("dev-b").version());
        LOG.info("输入 batchId=b-after-single 两台设备(dev-a 与单台路同内容/dev-b 全新)"
                + " 判定依据=dev-a 内容签名命中首次结论、dev-b 全新 最终结果=dev-a 幂等回放@v"
                + a.result().newVersion() + " 不重复下发，dev-b 正常生效@v1，两台互不串");
    }

    @Test
    void batchThenSingleSameContentReplaysInsteadOfStaleVersion() {
        ServiceHarness.caseHeader("先批量后单台：同内容被单台认出重复，回放首次结论而非版本冲突");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline("dev-c", 1);
        h.addRule("r-hot", "dev-c", "temperature", Operator.GTE, 30.0, "COOL_ON");

        // 批量路首次下发：生效，版本推进到 1，触发 1 条命令
        BatchDispatchResult batch = h.batches.dispatch(new BatchDesiredUpdate(
                "b-first", List.of("dev-c"), 30, Map.of("temperature", 33.0)));
        assertTrue(byDevice(batch, "dev-c").accepted());
        assertEquals(1, h.store.getOrThrow("dev-c").version());
        assertEquals(1, h.store.getOrThrow("dev-c").deliveredCommands().size());

        // 单台路原样再发同一份内容；客户端还拿着下发前的 baseVersion=0
        ShadowUpdateResult single = h.shadows.applyUpdate(
                new ShadowUpdate("dev-c", 0, "u-single-2", 30, Map.of("temperature", 33.0)));
        assertTrue(single.committed(), "同一份内容应回放首次成功结论，而不是 STALE_VERSION");
        assertTrue(single.idempotentReplay());
        assertEquals(1, single.newVersion());
        assertEquals(1, h.store.getOrThrow("dev-c").version(), "不得重复写入、版本不得凭空多涨");
        assertEquals(1, h.store.getOrThrow("dev-c").deliveredCommands().size(),
                "不得重复下发命令");
        LOG.info("输入 batchId=b-first 后单台同内容(baseVersion=0) 判定依据=内容签名命中批量首次结论"
                + " 最终结果=单台幂等回放@v1，版本保持1，命令保持1条");
    }

    @Test
    void rejectedDevicesReplaySameConclusionAcrossPaths() {
        ServiceHarness.caseHeader("首次被拒的设备：换路径重发同内容得到同一拒绝结论");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline("dev-unknown-field", 1);
        h.registerOnline("dev-type-mismatch", 1);
        // dev-ghost 保持未注册

        // 1) 未知字段：单台首次被拒 -> 批量同内容同因拒绝（幂等回放）
        ShadowUpdateResult unkSingle = h.shadows.applyUpdate(new ShadowUpdate(
                "dev-unknown-field", 0, "u-unk", 40, Map.of("ghost", 1)));
        assertEquals(RejectReason.UNKNOWN_FIELD, firstReason(unkSingle));
        BatchDispatchResult unkBatch = h.batches.dispatch(new BatchDesiredUpdate(
                "b-unk", List.of("dev-unknown-field"), 40, Map.of("ghost", 1)));
        BatchDeviceOutcome unk = byDevice(unkBatch, "dev-unknown-field");
        assertFalse(unk.accepted());
        assertEquals(RejectReason.UNKNOWN_FIELD, unk.reason(), "换批量路重发必须同因拒绝");
        assertTrue(unk.result().idempotentReplay());
        assertEquals(0, h.store.getOrThrow("dev-unknown-field").version(), "拒绝不得写入版本");

        // 2) 类型不符：批量首次被拒 -> 单台同内容同因拒绝（幂等回放）
        BatchDispatchResult typBatch = h.batches.dispatch(new BatchDesiredUpdate(
                "b-typ", List.of("dev-type-mismatch"), 41, Map.of("enabled", "yes")));
        assertEquals(RejectReason.TYPE_MISMATCH, byDevice(typBatch, "dev-type-mismatch").reason());
        ShadowUpdateResult typSingle = h.shadows.applyUpdate(new ShadowUpdate(
                "dev-type-mismatch", 0, "u-typ", 41, Map.of("enabled", "yes")));
        assertFalse(typSingle.committed());
        assertEquals(RejectReason.TYPE_MISMATCH, firstReason(typSingle));
        assertTrue(typSingle.idempotentReplay());
        assertEquals(0, h.store.getOrThrow("dev-type-mismatch").version());

        // 3) 未注册：批量首次被拒 -> 单台同内容同样明确拒绝（不抛异常、不写状态）
        BatchDispatchResult missBatch = h.batches.dispatch(new BatchDesiredUpdate(
                "b-miss", List.of("dev-ghost"), 42, Map.of("mode", "cool")));
        assertEquals(RejectReason.DEVICE_NOT_REGISTERED, byDevice(missBatch, "dev-ghost").reason());
        ShadowUpdateResult missSingle = h.shadows.applyUpdate(
                new ShadowUpdate("dev-ghost", 0, "u-miss", 42, Map.of("mode", "cool")));
        assertFalse(missSingle.committed());
        assertEquals(RejectReason.DEVICE_NOT_REGISTERED, firstReason(missSingle));

        // 4) 设备随后注册：同一份内容应按全新请求正常生效（未注册拒绝不按内容记忆）
        h.registerOnline("dev-ghost", 50);
        ShadowUpdateResult afterRegister = h.shadows.applyUpdate(
                new ShadowUpdate("dev-ghost", 0, "u-miss-2", 42, Map.of("mode", "cool")));
        assertTrue(afterRegister.committed(), "注册后同内容应正常生效，而非回放未注册拒绝");
        assertEquals(1, h.store.getOrThrow("dev-ghost").version());
        LOG.info("判定依据=拒绝结论跨路径按内容回放(未知字段/类型不符)；未注册不记内容记忆"
                + " 最终结果=两条路拒绝原因一致，注册后同内容正常生效@v1");
    }

    @Test
    void concurrentSingleAndBatchSameContentCommitExactlyOnce() throws Exception {
        ServiceHarness.caseHeader("单台与批量并发混跑同内容：同设备串行，恰好生效一次");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline("dev-race", 1);
        h.addRule("r-hot", "dev-race", "temperature", Operator.GTE, 30.0, "COOL_ON");

        int singles = 6;
        int batches = 6;
        ExecutorService pool = Executors.newFixedThreadPool(singles + batches);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ShadowUpdateResult> singleResults = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<BatchDispatchResult> batchResults = new ConcurrentLinkedQueue<>();

        // 同一份内容（同时间戳同 desired），单台路各用不同 updateId、批量路各用不同 batchId
        Map<String, Object> desired = Map.of("temperature", 35.0);
        for (int i = 0; i < singles; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    singleResults.add(h.shadows.applyUpdate(
                            new ShadowUpdate("dev-race", 0, "u-race-" + idx, 60, desired)));
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }
        for (int i = 0; i < batches; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    batchResults.add(h.batches.dispatch(new BatchDesiredUpdate(
                            "b-race-" + idx, List.of("dev-race"), 60, desired)));
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), "并发混跑不得抛异常: " + errors);

        // 最终状态确定：恰好生效一次
        assertEquals(1, h.store.getOrThrow("dev-race").version(),
                "同一份内容并发到达也只能写入一次，版本恰好为 1");
        assertEquals(1, h.store.getOrThrow("dev-race").deliveredCommands().size(),
                "命令恰好下发一次，不得重复");

        // 所有调用方都拿到成功结论，但其中恰好一次是真实提交，其余都是幂等回放
        long realCommits = 0;
        long replays = 0;
        for (ShadowUpdateResult r : singleResults) {
            assertTrue(r.committed(), "单台路每个调用方都应看到成功结论（首次或回放）");
            assertEquals(1, r.newVersion(), "结论版本号必须一致");
            if (r.idempotentReplay()) {
                replays++;
            } else {
                realCommits++;
            }
        }
        for (BatchDispatchResult r : batchResults) {
            BatchDeviceOutcome o = byDevice(r, "dev-race");
            assertTrue(o.accepted(), "批量路每台都应看到成功结论（首次或回放）");
            assertEquals(1, o.result().newVersion());
            if (o.result().idempotentReplay()) {
                replays++;
            } else {
                realCommits++;
            }
        }
        assertEquals(1, realCommits, "全部并发请求中恰好一次真实提交");
        assertEquals(singles + batches - 1, replays, "其余全部为幂等回放");
        LOG.info("输入 单台" + singles + "路+批量" + batches + "路并发同内容 判定依据=设备锁串行+内容幂等"
                + " 最终结果=真实提交" + realCommits + "次/回放" + replays
                + "次，版本=1，命令=1条，状态确定");
    }

    @Test
    void batchIdConflictStillRejectedAfterCrossPathCommits() {
        ServiceHarness.caseHeader("跨路径生效后：同 batchId 换内容仍 BATCH_ID_CONFLICT，不覆盖不写一半");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline("dev-x", 1);

        // 批量 b-mix 生效内容一；单台路再生效内容二，设备到 v2
        BatchDispatchResult first = h.batches.dispatch(new BatchDesiredUpdate(
                "b-mix", List.of("dev-x"), 70, Map.of("mode", "cool")));
        assertTrue(byDevice(first, "dev-x").accepted());
        ShadowUpdateResult single = h.shadows.applyUpdate(
                new ShadowUpdate("dev-x", 1, "u-mix", 71, Map.of("temperature", 25.0)));
        assertTrue(single.committed());
        assertEquals(2, h.store.getOrThrow("dev-x").version());

        // 同 batchId 换内容：明确拒绝，已有两次提交的状态不被覆盖
        BatchDispatchResult conflict = h.batches.dispatch(new BatchDesiredUpdate(
                "b-mix", List.of("dev-x"), 72, Map.of("mode", "heat")));
        assertFalse(conflict.accepted());
        assertEquals(RejectReason.BATCH_ID_CONFLICT, conflict.reason());
        assertEquals(2, h.store.getOrThrow("dev-x").version(), "冲突请求不得写入");
        assertEquals("cool", h.store.getOrThrow("dev-x").desired().get("mode").value());
        assertEquals(25.0, h.store.getOrThrow("dev-x").desired().get("temperature").value());

        // 原内容重放仍稳定回放首次结论
        BatchDispatchResult replay = h.batches.dispatch(new BatchDesiredUpdate(
                "b-mix", List.of("dev-x"), 70, Map.of("mode", "cool")));
        assertTrue(replay.idempotentReplay());
        assertEquals(2, h.store.getOrThrow("dev-x").version());
        LOG.info("输入 batchId=b-mix 先cool后换heat 判定依据=同 batchId 内容签名不一致"
                + " 最终结果=BATCH_ID_CONFLICT，v2(cool+25.0)保持不变，原内容重放仍幂等");
    }
}
