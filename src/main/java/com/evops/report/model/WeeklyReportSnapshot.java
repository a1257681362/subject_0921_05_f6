package com.evops.report.model;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 一家门店一个周期的周报数据快照（渲染前的中性结构，不含模板变量名）。
 * 返修率 = 返修单数 / 加工单总数；镜款分布为"镜款×数量"降序串。
 */
@Getter
@Setter
public class WeeklyReportSnapshot {
    private String storeCode;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private LocalDateTime generatedAt;
    private int totalCount;
    private int reworkCount;
    /** 如 12.50% */
    private String reworkRate;
    /** 如 1.60非球面×8；1.67非球面×3 */
    private String lensSummary;
    private List<WeeklyOrderRow> rows;
}
