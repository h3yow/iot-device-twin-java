package com.github.h3yow.iotdevicetwinjava.web;

import com.github.h3yow.iotdevicetwinjava.model.ShadowUpdate;
import com.github.h3yow.iotdevicetwinjava.model.ShadowUpdateResult;
import com.github.h3yow.iotdevicetwinjava.service.ShadowService;
import org.springframework.web.bind.annotation.*;

/** 影子 desired 区版本化合并接口。 */
@RestController
@RequestMapping("/api/shadows")
public class ShadowController {

    private final ShadowService shadowService;

    public ShadowController(ShadowService shadowService) {
        this.shadowService = shadowService;
    }

    @PostMapping("/update")
    public ShadowUpdateResult update(@RequestBody ShadowUpdate update) {
        return shadowService.applyUpdate(update);
    }
}
