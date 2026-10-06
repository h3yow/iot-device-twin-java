package com.github.h3yow.iotdevicetwinjava.service;

import com.github.h3yow.iotdevicetwinjava.model.BatchDesiredUpdate;
import com.github.h3yow.iotdevicetwinjava.model.BatchDeviceOutcome;
import com.github.h3yow.iotdevicetwinjava.model.BatchDispatchResult;
import com.github.h3yow.iotdevicetwinjava.model.FieldOutcome;
import com.github.h3yow.iotdevicetwinjava.model.RejectReason;
import com.github.h3yow.iotdevicetwinjava.model.ShadowUpdate;
import com.github.h3yow.iotdevicetwinjava.model.ShadowUpdateResult;
import com.github.h3yow.iotdevicetwinjava.store.DeviceShadowStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * 批量期望配置下发服务。
 *
 * 语义：
 * 1. 逐台隔离：同一份 desired 并发下发给清单内各设备，每台在各自设备锁内独立判定；
 *    一台被拒（未注册/版本不符/字段或类型问题/执行异常）不影响其他设备，
 *    判定依据、版本与命令严格按设备隔离（updateId = "batch:" + batchId + ":" + deviceId，
 *    ShadowService 的幂等记忆也按 (deviceId, updateId) 作用域）。
 * 2. 批次幂等：batchId + 请求内容（设备清单 + timestamp + desired）一致的重放，
 *    返回首次的逐台结论（idempotentReplay=true），不重复写状态、不重复下发命令；
 *    同 batchId 换内容整批以 BATCH_ID_CONFLICT 拒绝，绝不覆盖、绝不写一半。
 * 3. 容量与并发：单批设备数不得超过 maxBatchSize，超限整批 BATCH_TOO_LARGE；
 *    不同设备由线程池并发处理（慢设备只占自己的任务），同一设备仍由设备锁串行，
 *    多个批次并发也不会在同一台设备上互相踩踏。
 * 4. 跨路径一致：同一台设备收到与某次已处理更新内容（时间戳 + desired）完全相同
 *    的请求——无论来自批量还是单台接口——由 ShadowService 的内容幂等索引识别为
 *    重复，回放首次结论（含首次的拒绝结论），不重复写状态、不重复下发命令。
 */
public class BatchDispatchService {

    private static final Logger LOG = Logger.getLogger(BatchDispatchService.class.getName());

    /** 批量派生给单台更新的 updateId 前缀，保证与外部自带 updateId 不冲突。 */
    private static final String UPDATE_ID_PREFIX = "batch:";

    private final DeviceShadowStore store;
    private final ShadowService shadowService;
    private final int maxBatchSize;

    /** batchId -> 首次请求的内容签名，用于重放识别与标识冲突判定。 */
    private final Map<String, String> batchSignatures = new ConcurrentHashMap<>();
    /** batchId -> 首次逐台结论（不可变快照），重放直接回放，不再触碰任何设备状态。 */
    private final Map<String, BatchDispatchResult> batchResults = new ConcurrentHashMap<>();
    /** 同 batchId 的请求串行化（只影响重试/标识冲突识别，不影响其他批次）。 */
    private final Map<String, Object> batchLocks = new ConcurrentHashMap<>();

    private final ExecutorService executor;

    public BatchDispatchService(DeviceShadowStore store, ShadowService shadowService,
                                int maxBatchSize) {
        this(store, shadowService, maxBatchSize, defaultExecutor());
    }

    BatchDispatchService(DeviceShadowStore store, ShadowService shadowService, int maxBatchSize,
                         ExecutorService executor) {
        this.store = store;
        this.shadowService = shadowService;
        this.maxBatchSize = maxBatchSize;
        this.executor = executor;
    }

    private static ExecutorService defaultExecutor() {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "batch-dispatch-" + seq.incrementAndGet());
            // 守护线程：演示进程退出时不被线程池阻塞
            t.setDaemon(true);
            return t;
        };
        return Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), tf);
    }

    public BatchDispatchResult dispatch(BatchDesiredUpdate request) {
        if (request == null) {
            return BatchDispatchResult.rejected(null, RejectReason.BATCH_INVALID,
                    "请求体为空，整批拒绝");
        }
        String batchId = request.batchId();
        if (batchId == null || batchId.isBlank()) {
            return BatchDispatchResult.rejected(null, RejectReason.BATCH_INVALID,
                    "缺少 batchId，整批拒绝");
        }

        // ---- 结构性校验：不合法请求不落记忆、不处理任何一台 ----
        String invalid = validateShape(request);
        if (invalid != null) {
            LOG.info("[批量下发] batchId=" + batchId + " 判定依据=" + invalid
                    + " 触发结果=整批拒绝(BATCH_INVALID)，无任何设备副作用");
            return BatchDispatchResult.rejected(batchId, RejectReason.BATCH_INVALID, invalid);
        }
        if (request.deviceIds().size() > maxBatchSize) {
            String msg = "设备数=" + request.deviceIds().size() + " 超过单批上限 "
                    + maxBatchSize + "，整批拒绝（不处理其中任何一台）";
            LOG.info("[批量下发] batchId=" + batchId + " 判定依据=" + msg
                    + " 触发结果=整批拒绝(BATCH_TOO_LARGE)");
            return BatchDispatchResult.rejected(batchId, RejectReason.BATCH_TOO_LARGE, msg);
        }

        Object batchLock = batchLocks.computeIfAbsent(batchId, k -> new Object());
        synchronized (batchLock) {
            String signature = payloadSignature(request);
            String firstSignature = batchSignatures.get(batchId);
            if (firstSignature != null) {
                if (!firstSignature.equals(signature)) {
                    String msg = "batchId=" + batchId
                            + " 已被另一份内容（设备清单/时间戳/期望配置不同）使用，拒绝复用，"
                            + "已有批次结论保持不变";
                    LOG.info("[批量下发] batchId=" + batchId
                            + " 判定依据=同 batchId 内容签名不一致 触发结果=BATCH_ID_CONFLICT，不覆盖");
                    return BatchDispatchResult.rejected(batchId, RejectReason.BATCH_ID_CONFLICT, msg);
                }
                BatchDispatchResult first = batchResults.get(batchId);
                LOG.info("[批量重放] batchId=" + batchId + " 判定依据=batchId+内容一致(首次成功"
                        + first.successCount() + "台/拒绝" + first.rejectedCount() + "台)"
                        + " 触发结果=回放首次逐台结论，无设备副作用、不重复下发命令");
                // 逐台结论原样回放（与首次完全一致），仅顶层回放标记置 true
                return new BatchDispatchResult(first.batchId(), true, first.reason(), true,
                        first.successCount(), first.rejectedCount(), first.devices(),
                        first.message());
            }

            BatchDispatchResult result = execute(request);
            // 结论落记忆后才算批次完成；同一 synchronized 块内保证“先记忆后返回”，
            // 并发的同 batchId 请求要么一起等首次、要么看到完整结论，不会写一半。
            batchSignatures.put(batchId, signature);
            batchResults.put(batchId, result);
            LOG.info("[批量结论] batchId=" + batchId + " 设备数=" + request.deviceIds().size()
                    + " 成功=" + result.successCount() + " 拒绝=" + result.rejectedCount());
            return result;
        }
    }

    /** 结构性校验：空清单、空配置、重复设备均明确拒绝；返回 null 表示通过。 */
    private String validateShape(BatchDesiredUpdate request) {
        if (request.deviceIds() == null || request.deviceIds().isEmpty()) {
            return "设备清单为空，整批拒绝";
        }
        if (request.desired() == null || request.desired().isEmpty()) {
            return "期望配置 desired 为空，整批拒绝";
        }
        Set<String> unique = new HashSet<>();
        for (String id : request.deviceIds()) {
            if (id == null || id.isBlank()) {
                return "设备清单含空设备标识，整批拒绝";
            }
            if (!unique.add(id)) {
                return "设备清单含重复设备 '" + id + "'，整批拒绝（请去重后重试）";
            }
        }
        return null;
    }

    /** 并发逐台执行；每台一个独立任务，任何异常只隔离为该台失败。 */
    private BatchDispatchResult execute(BatchDesiredUpdate request) {
        List<String> deviceIds = request.deviceIds();
        List<CompletableFuture<BatchDeviceOutcome>> futures = new ArrayList<>(deviceIds.size());
        for (String deviceId : deviceIds) {
            futures.add(CompletableFuture.supplyAsync(() -> dispatchOne(request, deviceId), executor));
        }

        // 按请求清单顺序收集结论（join 等待，顺序确定），单台异常不外溢
        List<BatchDeviceOutcome> outcomes = new ArrayList<>(deviceIds.size());
        for (int i = 0; i < futures.size(); i++) {
            String deviceId = deviceIds.get(i);
            try {
                outcomes.add(futures.get(i).join());
            } catch (Exception e) {
                outcomes.add(failed(deviceId, e));
            }
        }

        int success = 0;
        int rejected = 0;
        for (BatchDeviceOutcome o : outcomes) {
            if (o.accepted()) {
                success++;
            } else {
                rejected++;
            }
        }
        String message = "批量受理并逐台完成：成功 " + success + " 台，拒绝 " + rejected
                + " 台；逐台结论见 devices（判定相互隔离、不串版本不串命令）";
        return new BatchDispatchResult(request.batchId(), true, null, false,
                success, rejected, List.copyOf(outcomes), message);
    }

    /** 单台下发：复用 ShadowService 的版本化合并（锁内取当前版本），结论彼此独立。 */
    private BatchDeviceOutcome dispatchOne(BatchDesiredUpdate request, String deviceId) {
        if (store.registration(deviceId).isEmpty()) {
            LOG.info("[批量逐台] batchId=" + request.batchId() + " device=" + deviceId
                    + " 判定依据=设备未注册 触发结果=DEVICE_NOT_REGISTERED，不影响其他设备");
            return new BatchDeviceOutcome(deviceId, false, RejectReason.DEVICE_NOT_REGISTERED,
                    unregisteredResult(deviceId, request.timestamp()));
        }

        String updateId = UPDATE_ID_PREFIX + request.batchId() + ":" + deviceId;
        ShadowUpdate update = new ShadowUpdate(deviceId, 0L, updateId,
                request.timestamp(), request.desired());
        ShadowUpdateResult result = shadowService.applyUpdateOnCurrentVersion(update);
        LOG.info("[批量逐台] batchId=" + request.batchId() + " device=" + deviceId
                + " 判定依据=" + (result.idempotentReplay() ? "幂等回放(该设备上同内容/同 updateId 的首次结论)"
                : result.committed() ? "当前版本校验通过且字段合法" : outcomeReasons(result))
                + " 最终结果=" + (result.committed() ? "生效@v" + result.newVersion()
                : "被拒(" + topReason(result) + ")"));
        return new BatchDeviceOutcome(deviceId, result.committed(),
                result.committed() ? null : topReason(result), result);
    }

    private BatchDeviceOutcome failed(String deviceId, Exception e) {
        LOG.warning("[批量逐台] device=" + deviceId + " 判定依据=执行异常:" + e
                + " 触发结果=隔离为该台 DEVICE_DISPATCH_FAILED，不影响其他设备");
        ShadowUpdateResult r = new ShadowUpdateResult(deviceId, false, 0L, 0L, false,
                List.of(FieldOutcome.rejected("*", null, null, null, null,
                        RejectReason.DEVICE_DISPATCH_FAILED,
                        "批量下发执行异常（已隔离）: " + e.getMessage())));
        return new BatchDeviceOutcome(deviceId, false, RejectReason.DEVICE_DISPATCH_FAILED, r);
    }

    private static ShadowUpdateResult unregisteredResult(String deviceId, long ts) {
        return new ShadowUpdateResult(deviceId, false, 0L, 0L, false,
                List.of(FieldOutcome.rejected("*", null, ts, null, null,
                        RejectReason.DEVICE_NOT_REGISTERED,
                        "设备 '" + deviceId + "' 未注册，跳过该台（其他设备不受影响）")));
    }

    private static RejectReason topReason(ShadowUpdateResult r) {
        return r.fields().stream()
                .map(FieldOutcome::reason)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(RejectReason.DEVICE_DISPATCH_FAILED);
    }

    private static String outcomeReasons(ShadowUpdateResult r) {
        return r.fields().stream()
                .filter(f -> f.reason() != null)
                .map(f -> f.field() + ":" + f.reason())
                .toList().toString();
    }

    /**
     * 批次内容签名：设备清单（顺序敏感）+ timestamp + 排序后的 desired。
     * 同一 batchId 的任何一项内容不同都会判定为 BATCH_ID_CONFLICT。
     */
    private static String payloadSignature(BatchDesiredUpdate request) {
        return String.join(",", request.deviceIds())
                + "|" + request.timestamp()
                + "|" + new TreeMap<>(request.desired());
    }
}
