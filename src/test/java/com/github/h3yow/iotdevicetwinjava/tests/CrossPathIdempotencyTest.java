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
 * 单台与批量两条下发路径对“同一台设备 + 同一份内容（时间戳与 desired 相同）”的
 * 一致性测试：无论先走哪条路、重放走哪条路，第二次都必须被识别为重复，
 * 直接回放首次结论——不写状态、不推进版本、不重复下发命令；首次被拒的内容
 * 跨路径重放也得到同一拒绝结论；并发混跑时同一台设备串行收敛、只提交一次。
 */
class CrossPathIdempotencyTest {

    private static final Logger LOG = Logger.getLogger(CrossPathIdempotencyTest.class.getName());

    private static final Map<String, Object> DESIRED = Map.of("temperature", 33.0);
    private static final long TS = 100L;

    private BatchDeviceOutcome byDevice(BatchDispatchResult r, String deviceId) {
        return r.devices().stream()
                .filter(d -> d.deviceId().equals(deviceId)).findFirst().orElseThrow();
    }

    private static RejectReason firstReason(ShadowUpdateResult r) {
        return r.fields().stream().map(FieldOutcome::reason)
                .filter(java.util.Objects::nonNull).findFirst().orElseThrow();
    }

    @Test
    void singleThenBatchSameContentReplaysFirstConclusion() {
        ServiceHarness.caseHeader("先单台后批量：同内容被识别为重复，回放首次结论");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline("dev-x", 1);
        h.addRule("r-hot", "dev-x", "temperature", Operator.GTE, 30.0, "COOL_ON");

        // 第一条路：单台下发成功，产生 1 条命令
        ShadowUpdateResult single = h.shadows.applyUpdate(
                new ShadowUpdate("dev-x", 0, "u-single-1", TS, DESIRED));
        assertTrue(single.committed());
        assertEquals(1, single.newVersion());
        assertEquals(1, h.store.getOrThrow("dev-x").deliveredCommands().size());

        // 第二条路：批量把同一份内容原样再发一遍
        BatchDispatchResult batch = h.batches.dispatch(
                new BatchDesiredUpdate("b-after-single", List.of("dev-x"), TS, DESIRED));
        BatchDeviceOutcome outcome = byDevice(batch, "dev-x");

        assertTrue(outcome.accepted(), "内容合法，批量结论仍是生效");
        assertTrue(outcome.result().idempotentReplay(),
                "同内容必须被识别为重复并标记幂等回放，而不是一次全新下发");
        assertEquals(single.newVersion(), outcome.result().newVersion(),
                "回放的是单台首次提交的结论（newVersion 一致）");
        assertEquals(1, h.store.getOrThrow("dev-x").version(), "版本不得因重复内容再涨");
        assertEquals(1, h.store.getOrThrow("dev-x").deliveredCommands().size(),
                "重复内容不得重复下发命令");
        LOG.info("输入 单台u-single-1提交后批量b-after-single同内容重发 判定依据=内容签名(时间戳+desired)命中首次更新"
                + " 最终结果=批量回放首次结论@v" + outcome.result().newVersion()
                + "，版本保持1，命令保持1条");
    }

    @Test
    void batchThenSingleSameContentReplaysInsteadOfStaleVersion() {
        ServiceHarness.caseHeader("先批量后单台：同内容回放首次结论，不误报 STALE_VERSION");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline("dev-y", 1);
        h.addRule("r-hot", "dev-y", "temperature", Operator.GTE, 30.0, "COOL_ON");

        // 第一条路：批量下发成功
        BatchDispatchResult batch = h.batches.dispatch(
                new BatchDesiredUpdate("b-first", List.of("dev-y"), TS, DESIRED));
        assertTrue(byDevice(batch, "dev-y").accepted());
        assertEquals(1, h.store.getOrThrow("dev-y").version());

        // 第二条路：单台带着批量之前的 baseVersion=0 发同一份内容。
        // 内容幂等判定先于版本校验：应回放批量首次结论，而不是 STALE_VERSION。
        ShadowUpdateResult single = h.shadows.applyUpdate(
                new ShadowUpdate("dev-y", 0, "u-single-2", TS, DESIRED));

        assertTrue(single.committed(), "同一份内容不应因路径不同而结论相反");
        assertTrue(single.idempotentReplay(), "必须识别为重复并回放，而非重新提交");
        assertEquals(1, single.newVersion());
        assertEquals(1, h.store.getOrThrow("dev-y").version(), "版本不得凭空多涨");
        assertEquals(1, h.store.getOrThrow("dev-y").deliveredCommands().size(),
                "命令不得重复下发");
        LOG.info("输入 批量b-first提交后单台u-single-2同内容(baseVersion=0) 判定依据=内容幂等命中批量首次更新"
                + " 最终结果=回放生效结论@v" + single.newVersion() + "，无 STALE_VERSION，版本/命令不重复");
    }

    @Test
    void rejectedContentReplaysConsistentlyAcrossPaths() {
        ServiceHarness.caseHeader("首次被拒的内容：跨路径重放得到同一拒绝结论，互不影响他台");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline("dev-unknown-field", 1);
        h.registerOnline("dev-type-mismatch", 1);
        h.registerOnline("dev-good", 1);
        // dev-ghost 不注册

        Map<String, Object> unknownFieldBody = Map.of("ghost", 1);
        Map<String, Object> typeMismatchBody = Map.of("enabled", "yes");

        // 1) 未知字段：先单台拒，再批量同内容重放
        ShadowUpdateResult singleRejected = h.shadows.applyUpdate(
                new ShadowUpdate("dev-unknown-field", 0, "u-bad-field", 10, unknownFieldBody));
        assertFalse(singleRejected.committed());
        assertEquals(RejectReason.UNKNOWN_FIELD, firstReason(singleRejected));

        BatchDispatchResult replayBatch = h.batches.dispatch(new BatchDesiredUpdate(
                "b-reject-replay", List.of("dev-unknown-field", "dev-good"), 10,
                unknownFieldBody));
        // 注意：dev-good 收到的是同一份含未知字段的内容，也应以 UNKNOWN_FIELD 被拒，
        // 且两台互不影响、各自结论独立。
        assertEquals(RejectReason.UNKNOWN_FIELD,
                byDevice(replayBatch, "dev-unknown-field").reason(),
                "换批量路径重放同内容，拒绝结论必须与单台首次一致");
        assertFalse(byDevice(replayBatch, "dev-unknown-field").result().committed());
        assertEquals(0, h.store.getOrThrow("dev-unknown-field").version(), "拒绝不得写入状态");

        // 2) 类型不符：先批量拒，再单台同内容重放
        BatchDispatchResult typeBatch = h.batches.dispatch(new BatchDesiredUpdate(
                "b-type-first", List.of("dev-type-mismatch"), 11, typeMismatchBody));
        assertEquals(RejectReason.TYPE_MISMATCH, byDevice(typeBatch, "dev-type-mismatch").reason());

        ShadowUpdateResult typeSingle = h.shadows.applyUpdate(
                new ShadowUpdate("dev-type-mismatch", 0, "u-type-replay", 11, typeMismatchBody));
        assertFalse(typeSingle.committed(), "换单台路径重放被拒内容，不得又被写进去");
        assertTrue(typeSingle.idempotentReplay());
        assertEquals(RejectReason.TYPE_MISMATCH, firstReason(typeSingle));
        assertEquals(0, h.store.getOrThrow("dev-type-mismatch").version());

        // 3) 未注册设备：两条路都给 DEVICE_NOT_REGISTERED
        ShadowUpdateResult ghostSingle = h.shadows.applyUpdate(
                new ShadowUpdate("dev-ghost", 0, "u-ghost", 12, Map.of("mode", "cool")));
        assertFalse(ghostSingle.committed());
        assertEquals(RejectReason.DEVICE_NOT_REGISTERED, firstReason(ghostSingle));
        BatchDispatchResult ghostBatch = h.batches.dispatch(new BatchDesiredUpdate(
                "b-ghost", List.of("dev-ghost", "dev-good"), 12, Map.of("mode", "cool")));
        assertEquals(RejectReason.DEVICE_NOT_REGISTERED, byDevice(ghostBatch, "dev-ghost").reason());
        // 同批里的正常设备不受未注册设备影响
        assertTrue(byDevice(ghostBatch, "dev-good").accepted());
        assertEquals(1, h.store.getOrThrow("dev-good").version());

        LOG.info("输入 未知字段/类型不符/未注册 三类首次拒绝后跨路径重放 判定依据=内容索引记忆首次拒绝结论"
                + " 最终结果=重放同因拒绝、版本保持0，同批正常设备 dev-good 生效@v1 不受牵连");
    }

    @Test
    void concurrentSingleAndBatchSameContentCommitExactlyOnce() throws Exception {
        ServiceHarness.caseHeader("单台与批量并发混跑同内容：同一台设备只提交一次");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline("dev-race", 1);
        h.addRule("r-hot", "dev-race", "temperature", Operator.GTE, 30.0, "COOL_ON");

        int singlePaths = 6;
        int batchPaths = 6;
        ExecutorService pool = Executors.newFixedThreadPool(singlePaths + batchPaths);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<ShadowUpdateResult> singleResults = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<BatchDispatchResult> batchResults = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < singlePaths; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 每个并发请求用不同 updateId，逼出“内容幂等”而不是 updateId 幂等
                    singleResults.add(h.shadows.applyUpdate(
                            new ShadowUpdate("dev-race", 0, "u-race-" + idx, TS, DESIRED)));
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }
        for (int i = 0; i < batchPaths; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    batchResults.add(h.batches.dispatch(new BatchDesiredUpdate(
                            "b-race-" + idx, List.of("dev-race"), TS, DESIRED)));
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), "并发混跑不得抛异常: " + errors);
        assertEquals(singlePaths, singleResults.size());
        assertEquals(batchPaths, batchResults.size());

        // 全部结论都是“生效”，但其中恰好一次是真实提交，其余全是幂等回放
        long singleCommits = singleResults.stream()
                .filter(r -> r.committed() && !r.idempotentReplay()).count();
        long singleReplays = singleResults.stream()
                .filter(r -> r.committed() && r.idempotentReplay()).count();
        long batchCommits = batchResults.stream()
                .map(r -> byDevice(r, "dev-race"))
                .filter(o -> o.accepted() && !o.result().idempotentReplay()).count();
        long batchReplays = batchResults.stream()
                .map(r -> byDevice(r, "dev-race"))
                .filter(o -> o.accepted() && o.result().idempotentReplay()).count();

        assertEquals(1, singleCommits + batchCommits,
                "同一份内容并发打到同一台设备，恰好只能提交一次");
        assertEquals(singlePaths + batchPaths - 1, singleReplays + batchReplays,
                "其余并发请求必须全部识别为重复并回放首次结论");
        for (ShadowUpdateResult r : singleResults) {
            assertEquals(1, r.newVersion(), "所有结论都指向同一个首次提交版本");
        }
        assertEquals(1, h.store.getOrThrow("dev-race").version(),
                "版本恰好推进一次，不凭空多涨");
        assertEquals(1, h.store.getOrThrow("dev-race").deliveredCommands().size(),
                "命令恰好下发一次，不重复");
        LOG.info("输入 单台x" + singlePaths + "+批量x" + batchPaths + " 并发同内容 判定依据=设备锁串行+内容幂等索引"
                + " 最终结果=真实提交" + (singleCommits + batchCommits) + "次 回放"
                + (singleReplays + batchReplays) + "次，版本=1，命令=1条");
    }

    @Test
    void sameBatchIdDifferentContentStillRejectedAfterCrossPathFix() {
        ServiceHarness.caseHeader("跨路径内容幂等不削弱批次标识冲突：同 batchId 换内容仍拒绝");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline("dev-z", 1);

        BatchDispatchResult first = h.batches.dispatch(
                new BatchDesiredUpdate("b-guard", List.of("dev-z"), TS, DESIRED));
        assertTrue(byDevice(first, "dev-z").accepted());

        // 同 batchId 换内容：仍必须 BATCH_ID_CONFLICT，不覆盖已有结论
        BatchDispatchResult conflict = h.batches.dispatch(new BatchDesiredUpdate(
                "b-guard", List.of("dev-z"), TS + 1, Map.of("temperature", 25.0)));
        assertFalse(conflict.accepted());
        assertEquals(RejectReason.BATCH_ID_CONFLICT, conflict.reason());
        assertEquals(33.0, h.store.getOrThrow("dev-z").desired().get("temperature").value(),
                "冲突批次不得覆盖已生效内容");
        assertEquals(1, h.store.getOrThrow("dev-z").version());

        // 不同 batchId 换内容：属于新内容，正常生效（验证内容幂等不误伤新内容）
        BatchDispatchResult newContent = h.batches.dispatch(new BatchDesiredUpdate(
                "b-guard-2", List.of("dev-z"), TS + 1, Map.of("temperature", 25.0)));
        assertTrue(byDevice(newContent, "dev-z").accepted());
        assertFalse(byDevice(newContent, "dev-z").result().idempotentReplay(),
                "内容不同的更新必须真实提交，不能被内容索引误伤");
        assertEquals(2, h.store.getOrThrow("dev-z").version());
        assertEquals(25.0, h.store.getOrThrow("dev-z").desired().get("temperature").value());
        LOG.info("判定依据=batchId 冲突校验独立于内容幂等 最终结果=同 batchId 换内容拒绝且不覆盖，"
                + "新 batchId 新内容正常生效@v2");
    }
}
