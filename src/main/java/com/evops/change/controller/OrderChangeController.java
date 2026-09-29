package com.evops.change.controller;

import com.evops.change.dto.ChangeCreateRequest;
import com.evops.change.dto.ChangeReviewRequest;
import com.evops.change.dto.ChangeScheduleRequest;
import com.evops.change.entity.OrderChange;
import com.evops.change.service.OrderChangeService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 加工单变更单接口（验光师改方/换镜款评审）。
 * 写接口同样携带 X-Operator-Id / X-Request-No / X-Biz-Time 审计头，request_no 唯一约束兜底重复提交。
 * 变更单不直接改加工单：评审通过后验光师仍走既有 PUT /api/orders 落库，加工单写路径不变。
 */
@RestController
@RequestMapping("/api/order-changes")
public class OrderChangeController {

    private final OrderChangeService changeService;

    public OrderChangeController(OrderChangeService changeService) {
        this.changeService = changeService;
    }

    /** 提交变更单（changeType=REWRITE 改方 / ALTER 换镜款），返回待评审变更单 */
    @PostMapping
    public ApiResponse<OrderChange> submit(@Valid @RequestBody ChangeCreateRequest request) {
        return ApiResponse.ok(changeService.submit(request));
    }

    /** 安排验光师评审时间（仅待评审单，可改期） */
    @PostMapping("/{changeNo}/schedule")
    public ApiResponse<OrderChange> schedule(@PathVariable String changeNo,
                                             @Valid @RequestBody ChangeScheduleRequest request) {
        return ApiResponse.ok(changeService.schedule(changeNo, request));
    }

    /** 录入评审结论：{"approved":true,"reviewComment":"..."}，须先安排评审时间 */
    @PostMapping("/{changeNo}/review")
    public ApiResponse<OrderChange> review(@PathVariable String changeNo,
                                           @Valid @RequestBody ChangeReviewRequest request) {
        return ApiResponse.ok(changeService.review(changeNo, request));
    }

    /** 按变更单号查询 */
    @GetMapping("/by-no/{changeNo}")
    public ApiResponse<OrderChange> getByNo(@PathVariable String changeNo) {
        return ApiResponse.ok(changeService.getByChangeNo(changeNo));
    }

    /** 列表：可按评审状态/加工单号/门店过滤 */
    @GetMapping
    public ApiResponse<List<OrderChange>> list(@RequestParam(required = false) String status,
                                               @RequestParam(required = false) String orderNo,
                                               @RequestParam(required = false) String storeCode) {
        return ApiResponse.ok(changeService.list(status, orderNo, storeCode));
    }
}
