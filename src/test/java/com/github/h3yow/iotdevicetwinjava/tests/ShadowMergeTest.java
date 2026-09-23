package com.github.h3yow.iotdevicetwinjava.tests;

import com.github.h3yow.iotdevicetwinjava.model.FieldOutcome;
import com.github.h3yow.iotdevicetwinjava.model.RejectReason;
import com.github.h3yow.iotdevicetwinjava.model.ShadowUpdate;
import com.github.h3yow.iotdevicetwinjava.model.ShadowUpdateResult;
import com.github.h3yow.iotdevicetwinjava.tests.support.ServiceHarness;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 影子版本化合并测试：乐观版本、未知字段、类型不匹配、幂等重放、updateId 冲突，
 * 失败原因必须可区分，重放结论一致。
 */
class ShadowMergeTest {

    private static final Logger LOG = Logger.getLogger(ShadowMergeTest.class.getName());
    private static final String DEVICE = "dev-B";

    private ShadowUpdateResult update(ServiceHarness h, String updateId, long base, long ts,
                                      Map<String, Object> desired) {
        return h.shadows.applyUpdate(new ShadowUpdate(DEVICE, base, updateId, ts, desired));
    }

    private RejectReason firstReason(ShadowUpdateResult r) {
        return r.fields().stream().map(FieldOutcome::reason).findFirst().orElseThrow();
    }

    @Test
    void staleVersionIsRejectedWithoutPartialWrite() {
        ServiceHarness.caseHeader("落后版本整批拒绝，无部分写入");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline(DEVICE, 1);

        ShadowUpdateResult ok = update(h, "u1", 0, 10, Map.of("mode", "cool"));
        assertTrue(ok.committed());
        assertEquals(1, ok.newVersion());

        // 另一客户端拿着过期 baseVersion=0 提交，且夹带未知字段：版本原因优先且可区分
        ShadowUpdateResult stale = update(h, "u2", 0, 11, Map.of("mode", "heat", "ghost", 1));
        assertFalse(stale.committed());
        assertEquals(RejectReason.STALE_VERSION, firstReason(stale));
        assertEquals(1, h.store.getOrThrow(DEVICE).version());
        assertEquals("cool", h.store.getOrThrow(DEVICE).desired().get("mode").value(),
                "整批拒绝不得发生部分写入");
        LOG.info("输入 baseVersion=0 currentVersion=1 判定依据=STALE_VERSION 触发结果=无写入，版本保持 1");
    }

    @Test
    void unknownFieldAndTypeMismatchReasonsAreDistinguishable() {
        ServiceHarness.caseHeader("影子更新 schema 拒绝原因可区分");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline(DEVICE, 1);

        ShadowUpdateResult badField = update(h, "u1", 0, 10, Map.of("notExists", "x"));
        ShadowUpdateResult badType = update(h, "u2", 0, 11, Map.of("enabled", "yes"));

        assertEquals(RejectReason.UNKNOWN_FIELD, firstReason(badField));
        assertEquals(RejectReason.TYPE_MISMATCH, firstReason(badType));
        assertEquals(0, h.store.getOrThrow(DEVICE).version(), "两类拒绝都不得推进版本");
        LOG.info("判定依据: [" + badField.fields().get(0).basis() + "] / ["
                + badType.fields().get(0).basis() + "]");
    }

    @Test
    void sameUpdateReplayGivesSameConclusionAndNoSideEffects() {
        ServiceHarness.caseHeader("同一更新重放结论一致，不重复触发命令");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline(DEVICE, 1);
        // desired 写入 temperature 后 merged 视图命中阈值规则
        h.addRule("r-cool", DEVICE, "temperature",
                com.github.h3yow.iotdevicetwinjava.model.Operator.GTE, 30.0, "COOL_ON");

        ShadowUpdate update = new ShadowUpdate(DEVICE, 0, "upd-9", 20, Map.of("temperature", 31.0));
        ShadowUpdateResult first = h.shadows.applyUpdate(update);
        ShadowUpdateResult second = h.shadows.applyUpdate(update);
        ShadowUpdateResult third = h.shadows.applyUpdate(update);

        assertTrue(first.committed());
        assertTrue(second.idempotentReplay() && third.idempotentReplay());
        assertEquals(first.newVersion(), second.newVersion());
        assertEquals(1, h.store.getOrThrow(DEVICE).version(), "重放不推进版本");
        assertEquals(1, h.store.getOrThrow(DEVICE).deliveredCommands().size(),
                "重放不得重复下发命令");
        assertEquals(1, h.store.getOrThrow(DEVICE).deliveredDedupKeys().size());
        LOG.info("输入时间戳=20 判定依据=updateId+请求体一致 触发结果=仅首次触发 1 条命令，重放结论一致");
    }

    @Test
    void reusedUpdateIdWithDifferentBodyIsRejectedAsConflict() {
        ServiceHarness.caseHeader("updateId 被不同请求体复用按 UPDATE_ID_CONFLICT 拒绝");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline(DEVICE, 1);

        ShadowUpdateResult first = update(h, "same-id", 0, 10, Map.of("mode", "cool"));
        assertTrue(first.committed());
        ShadowUpdateResult conflict = update(h, "same-id", 1, 12, Map.of("mode", "heat"));
        assertFalse(conflict.committed());
        assertEquals(RejectReason.UPDATE_ID_CONFLICT, firstReason(conflict));
        assertEquals("cool", h.store.getOrThrow(DEVICE).desired().get("mode").value());
        LOG.info("判定依据=updateId 相同但请求体签名不同 触发结果=UPDATE_ID_CONFLICT，状态保持 cool");
    }

    @Test
    void rejectedBatchReplayAlsoStable() {
        ServiceHarness.caseHeader("被拒绝的批次重放仍得到同一拒绝结论");
        ServiceHarness h = new ServiceHarness();
        h.registerOnline(DEVICE, 1);

        ShadowUpdate bad = new ShadowUpdate(DEVICE, 9, "u-stale", 5, Map.of("mode", "cool"));
        ShadowUpdateResult r1 = h.shadows.applyUpdate(bad);
        ShadowUpdateResult r2 = h.shadows.applyUpdate(bad);

        assertFalse(r1.committed());
        assertEquals(r1.committed(), r2.committed());
        assertEquals(r1.newVersion(), r2.newVersion());
        assertTrue(r2.idempotentReplay());
        assertEquals(RejectReason.STALE_VERSION, firstReason(r2));
        LOG.info("判定依据=拒绝结论也按 updateId 记忆 触发结果=重放返回首次结论 STALE_VERSION");
    }
}
