package com.github.h3yow.iotdevicetwinjava.web;

import com.github.h3yow.iotdevicetwinjava.model.BatchShadowUpdate;
import com.github.h3yow.iotdevicetwinjava.model.BatchUpdateResult;
import com.github.h3yow.iotdevicetwinjava.model.ShadowUpdate;
import com.github.h3yow.iotdevicetwinjava.model.ShadowUpdateResult;
import com.github.h3yow.iotdevicetwinjava.service.BatchShadowService;
import com.github.h3yow.iotdevicetwinjava.service.ShadowService;
import org.springframework.web.bind.annotation.*;

/** 影子 desired 区版本化合并接口（单台 + 批量）。 */
@RestController
@RequestMapping("/api/shadows")
public class ShadowController {

    private final ShadowService shadowService;
    private final BatchShadowService batchShadowService;

    public ShadowController(ShadowService shadowService, BatchShadowService batchShadowService) {
        this.shadowService = shadowService;
        this.batchShadowService = batchShadowService;
    }

    @PostMapping("/update")
    public ShadowUpdateResult update(@RequestBody ShadowUpdate update) {
        return shadowService.applyUpdate(update);
    }

    /** 批量期望配置下发：同一份 desired 发给一批设备，逐台返回结论。 */
    @PostMapping("/batch-update")
    public BatchUpdateResult batchUpdate(@RequestBody BatchShadowUpdate batch) {
        return batchShadowService.applyBatch(batch);
    }
}
