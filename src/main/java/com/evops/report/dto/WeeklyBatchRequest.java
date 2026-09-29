package com.evops.report.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/**
 * 批量按门店出周报：storeCodes 为空则导出周期内有建档数据的全部门店（各出一个 docx 打 ZIP）。
 */
@Getter
@Setter
public class WeeklyBatchRequest {

    /** 指定门店集合；空 = 周期内有单的全部门店 */
    private List<String> storeCodes;

    @NotNull(message = "统计起始日期不能为空")
    private LocalDate periodStart;

    @NotNull(message = "统计截止日期不能为空")
    private LocalDate periodEnd;
}
