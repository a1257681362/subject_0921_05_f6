package com.evops.change.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/**
 * 录入验光师评审结论。approved=true 评审通过，false 驳回；
 * reviewerId 可空，空取请求头 X-Operator-Id。
 */
@Getter
@Setter
public class ChangeReviewRequest {

    @NotNull(message = "评审结论不能为空")
    private Boolean approved;

    /** 评审人；空则取 X-Operator-Id */
    private Long reviewerId;

    @Size(max = 500)
    private String reviewComment;
}
