package com.github.h3yow.iotdevicetwinjava.web;

import com.github.h3yow.iotdevicetwinjava.model.BatchDesiredUpdate;
import com.github.h3yow.iotdevicetwinjava.model.BatchDispatchResult;
import com.github.h3yow.iotdevicetwinjava.service.BatchDispatchService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 批量期望配置下发接口。 */
@RestController
@RequestMapping("/api/shadows")
public class BatchController {

    private final BatchDispatchService batchDispatchService;

    public BatchController(BatchDispatchService batchDispatchService) {
        this.batchDispatchService = batchDispatchService;
    }

    @PostMapping("/batch-update")
    public BatchDispatchResult batchUpdate(@RequestBody BatchDesiredUpdate request) {
        return batchDispatchService.dispatch(request);
    }
}
