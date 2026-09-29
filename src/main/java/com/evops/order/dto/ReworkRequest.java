package com.evops.order.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.Size;

/**
 * 返修登记请求体。仅已取镜（PICKED_UP）加工单可登记；
 * 返修不改加工单状态；reason 选填，versionNo 可选（传入则做乐观并发校验）。
 */
@Getter
@Setter
public class ReworkRequest {
    @Size(max = 255)
    private String reason;
    private Long versionNo;
}
