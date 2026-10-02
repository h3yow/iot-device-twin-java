package com.github.h3yow.iotdevicetwinjava.service;

import com.github.h3yow.iotdevicetwinjava.model.BatchShadowUpdate;
import com.github.h3yow.iotdevicetwinjava.model.BatchUpdateResult;
import com.github.h3yow.iotdevicetwinjava.model.FieldOutcome;
import com.github.h3yow.iotdevicetwinjava.model.RejectReason;
import com.github.h3yow.iotdevicetwinjava.model.ShadowUpdate;
import com.github.h3yow.iotdevicetwinjava.model.ShadowUpdateResult;
import com.github.h3yow.iotdevicetwinjava.store.DeviceShadowStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * 批量期望配置下发服务：把同一份 desired 一次发给一批设备，逐台给出结论。
 *
 * 语义：
 * 1. 逐台隔离：每台设备独立走 {@link ShadowService#applyUpdate}（设备锁内串行），
 *    某台失败（UNKNOWN_FIELD / TYPE_MISMATCH / STALE_VERSION / DEVICE_NOT_FOUND）
 *    不影响其他台；结论按 deviceId 排序返回，不会把一台的判定或版本串到另一台。
 * 2. 批次幂等：batchId 是批次级幂等键。同 batchId + 同内容（含重试并发到达）
 *    只执行一次，所有调用方拿到同一份逐台结论（重放 idempotentReplay=true），
 *    不重复写状态、不重复下发命令；同 batchId 不同内容以 BATCH_ID_CONFLICT
 *    整批拒绝，不覆盖已存结论、不写一半。
 * 3. 容量上限：设备数超过 {@link #MAX_BATCH_SIZE} 或为空时整批拒绝
 *    （BATCH_TOO_LARGE / EMPTY_BATCH），不处理任何子集；校验失败的请求不占用 batchId。
 * 4. 并发：不同设备由线程池并行处理，同一设备上的多个批次靠设备锁串行，
 *    不会出现同设备两个批次互相踩踏；单台处理慢不会卡住整批其他设备。
 * 5. 与单台接口的关系：批量对每台设备以 batchId 作为 updateId 走同一条
 *    ShadowService 判定链路，因此两条路径对同一台设备重放同一份内容时
 *    结论一致（内容签名重放，见 ShadowService）。
 */
public class BatchShadowService {

    private static final Logger LOG = Logger.getLogger(BatchShadowService.class.getName());

    /** 单批设备数上限，超过整批拒绝。 */
    public static final int MAX_BATCH_SIZE = 500;

    private final DeviceShadowStore store;
    private final ShadowService shadowService;
    private final Executor deviceExecutor;

    /** batchId -> 批次执行记录（签名 + 结果 future）。首次创建者负责执行，其余调用方等待同一结果。 */
    private final ConcurrentHashMap<String, BatchExecution> executions = new ConcurrentHashMap<>();

    private record BatchExecution(String signature, CompletableFuture<BatchUpdateResult> future) {
    }

    public BatchShadowService(DeviceShadowStore store, ShadowService shadowService,
                              Executor deviceExecutor) {
        this.store = store;
        this.shadowService = shadowService;
        this.deviceExecutor = deviceExecutor;
    }

    /** 默认设备并行执行器（守护线程，测试与本地运行不阻塞 JVM 退出）。 */
    public static Executor defaultDeviceExecutor() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "batch-shadow-worker-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newFixedThreadPool(Math.max(8, Runtime.getRuntime().availableProcessors()), factory);
    }

    public BatchUpdateResult applyBatch(BatchShadowUpdate batch) {
        List<String> deviceIds = batch.deviceIds() == null ? List.of() : batch.deviceIds();
        LOG.info("[批量下发] batchId=" + batch.batchId() + " baseVersion=" + batch.baseVersion()
                + " 输入时间戳=" + batch.timestamp() + " 设备数=" + deviceIds.size()
                + " desired=" + new TreeMap<>(batch.desired()));

        // ---- 批次级校验（确定性校验，失败的请求不占用 batchId） ----
        if (deviceIds.isEmpty()) {
            LOG.info("[批量结论] batchId=" + batch.batchId()
                    + " 判定依据=EMPTY_BATCH(设备清单为空) 触发结果=整批拒绝，未处理任何设备");
            return BatchUpdateResult.rejected(batch.batchId(), RejectReason.EMPTY_BATCH,
                    "设备清单为空，整批拒绝");
        }
        if (deviceIds.size() > MAX_BATCH_SIZE) {
            LOG.info("[批量结论] batchId=" + batch.batchId()
                    + " 判定依据=BATCH_TOO_LARGE(设备数=" + deviceIds.size() + " > 上限="
                    + MAX_BATCH_SIZE + ") 触发结果=整批拒绝，未处理任何设备");
            return BatchUpdateResult.rejected(batch.batchId(), RejectReason.BATCH_TOO_LARGE,
                    "设备数 " + deviceIds.size() + " 超过单批上限 " + MAX_BATCH_SIZE
                            + "，整批拒绝（不处理任何子集），请拆分后重试");
        }

        // ---- 批次幂等：同 batchId 只执行一次 ----
        String signature = payloadSignature(batch);
        BatchExecution fresh = new BatchExecution(signature, new CompletableFuture<>());
        BatchExecution existing = executions.putIfAbsent(batch.batchId(), fresh);
        if (existing != null) {
            if (!existing.signature().equals(signature)) {
                LOG.info("[批量结论] batchId=" + batch.batchId()
                        + " 判定依据=BATCH_ID_CONFLICT(batchId 已被另一份内容的请求占用)"
                        + " 触发结果=整批拒绝，不覆盖已存结论、不写任何设备");
                return BatchUpdateResult.rejected(batch.batchId(), RejectReason.BATCH_ID_CONFLICT,
                        "batchId=" + batch.batchId() + " 已用于另一份内容的批量请求，拒绝复用；"
                                + "已存结论不受影响，请更换批次标识");
            }
            BatchUpdateResult replayed = existing.future().join();
            LOG.info("[批量结论] batchId=" + batch.batchId()
                    + " 判定依据=batchId+内容签名与首次一致 触发结果=回放首次逐台结论(生效 "
                    + replayed.committedCount() + " 拒绝 " + replayed.rejectedCount()
                    + ")，不重复写状态、不重复下发命令");
            return new BatchUpdateResult(replayed.batchId(), replayed.batchRejected(),
                    replayed.rejectReason(), replayed.basis(), true, replayed.deviceResults());
        }

        // ---- 首次执行：本调用方负责跑完整批 ----
        try {
            BatchUpdateResult result = executeBatch(batch, deviceIds);
            fresh.future().complete(result);
            return result;
        } catch (RuntimeException e) {
            // 执行异常：移除占位，允许客户端重试（设备级幂等保证重试安全）
            executions.remove(batch.batchId(), fresh);
            fresh.future().completeExceptionally(e);
            throw e;
        }
    }

    private BatchUpdateResult executeBatch(BatchShadowUpdate batch, List<String> deviceIds) {
        // 清单内重复设备去重（保持顺序），同一台设备在一批内只判定一次
        List<String> distinctDevices = new ArrayList<>(new LinkedHashSet<>(deviceIds));

        // 不同设备并行；同一设备上的串行由设备锁保证
        List<CompletableFuture<ShadowUpdateResult>> futures = distinctDevices.stream()
                .map(deviceId -> CompletableFuture.supplyAsync(
                        () -> applyToDevice(batch, deviceId), deviceExecutor))
                .toList();
        List<ShadowUpdateResult> results = new ArrayList<>(
                futures.stream().map(CompletableFuture::join).toList());
        results.sort(Comparator.comparing(ShadowUpdateResult::deviceId));

        BatchUpdateResult result = new BatchUpdateResult(batch.batchId(), false, null, null,
                false, List.copyOf(results));
        LOG.info("[批量结论] batchId=" + batch.batchId() + " 判定依据=逐台独立判定(设备锁内串行)"
                + " 触发结果=生效 " + result.committedCount() + " 台，拒绝 " + result.rejectedCount()
                + " 台，明细=" + perDeviceSummary(result));
        return result;
    }

    /** 单台设备下发：未注册设备按 DEVICE_NOT_FOUND 逐台拒绝，不影响其他设备。 */
    private ShadowUpdateResult applyToDevice(BatchShadowUpdate batch, String deviceId) {
        if (store.registration(deviceId).isEmpty()) {
            LOG.info("[批量逐台] batchId=" + batch.batchId() + " device=" + deviceId
                    + " 判定依据=DEVICE_NOT_FOUND(设备未注册) 触发结果=该台拒绝，不影响其他设备");
            return new ShadowUpdateResult(deviceId, false, -1, -1, false,
                    List.of(FieldOutcome.rejected("*", null, batch.timestamp(), null, null,
                            RejectReason.DEVICE_NOT_FOUND, "设备 " + deviceId + " 未注册，该台拒绝")));
        }
        // 批量路径以 batchId 作为该设备的 updateId，与单台路径共用同一套判定与幂等记忆
        ShadowUpdate perDevice = new ShadowUpdate(deviceId, batch.baseVersion(),
                batch.batchId(), batch.timestamp(), batch.desired());
        ShadowUpdateResult r = shadowService.applyUpdate(perDevice);
        LOG.info("[批量逐台] batchId=" + batch.batchId() + " device=" + deviceId
                + " 判定依据=" + (r.committed() ? "版本匹配且字段全部合法" : rejectBasis(r))
                + " 触发结果=" + (r.committed() ? "生效 v" + r.currentVersion() + "->v" + r.newVersion()
                : "拒绝(currentVersion=" + r.currentVersion() + ")")
                + (r.idempotentReplay() ? " [幂等重放]" : ""));
        return r;
    }

    private static String rejectBasis(ShadowUpdateResult r) {
        return r.fields().stream()
                .filter(f -> f.reason() != null)
                .map(f -> f.field() + ":" + f.reason())
                .toList().toString();
    }

    private static String perDeviceSummary(BatchUpdateResult result) {
        return result.deviceResults().stream()
                .map(r -> r.deviceId() + "=" + (r.committed() ? "生效@v" + r.newVersion()
                        : "拒绝(" + rejectBasis(r) + ")"))
                .toList().toString();
    }

    /** 批次内容签名：batchId 之外的全部请求内容。 */
    private static String payloadSignature(BatchShadowUpdate batch) {
        List<String> sortedDevices = batch.deviceIds() == null ? List.of()
                : batch.deviceIds().stream().sorted().distinct().toList();
        return batch.baseVersion() + "|" + batch.timestamp() + "|"
                + new TreeMap<>(batch.desired()) + "|" + sortedDevices;
    }
}
