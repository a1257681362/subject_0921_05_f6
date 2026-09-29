package com.evops.change.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 安排验光师评审时间（仅待评审变更单可安排）。
 */
@Getter
@Setter
public class ChangeScheduleRequest {

    @NotNull(message = "评审时间不能为空")
    private LocalDateTime reviewTime;
}
