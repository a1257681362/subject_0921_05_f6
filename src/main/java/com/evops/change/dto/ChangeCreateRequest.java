package com.evops.change.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 验光师提交加工单变更单。
 * changeType：REWRITE 改方（默认）/ ALTER 换镜款；
 * storeCode 可空，空则取加工单所属门店。
 */
@Getter
@Setter
public class ChangeCreateRequest {

    @NotBlank(message = "变更单号不能为空")
    @Size(max = 64)
    private String changeNo;

    @NotBlank(message = "加工单号不能为空")
    @Size(max = 64)
    private String orderNo;

    /** REWRITE 改方 / ALTER 换镜款；空按改方处理 */
    @Size(max = 16)
    private String changeType;

    @Size(max = 32)
    private String storeCode;

    @NotBlank(message = "变更原因不能为空")
    @Size(max = 500)
    private String reason;
}
