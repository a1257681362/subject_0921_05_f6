package com.evops.report.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 加工单周汇总报告数据（一家门店一份）。键名即模板变量：
 * 抬头（storeCode/periodStart/periodEnd/generatedAt）、汇总（orderCount/reworkOrderCount/reworkRate…）、
 * 明细行集合 orders（模板中用行循环展开）。
 */
@Getter
@Setter
public class WeeklyReportData {
    /** 门店编码 */
    private String storeCode;
    /** 统计周期起 yyyy-MM-dd */
    private String periodStart;
    /** 统计周期止 yyyy-MM-dd */
    private String periodEnd;
    /** 报告生成时间 yyyy-MM-dd HH:mm */
    private String generatedAt;

    /** 取镜加工单总数（明细行数） */
    private int orderCount;
    /** 其中发生返修的加工单数 */
    private int reworkOrderCount;
    /** 返修总次数（一单可多次返修） */
    private int reworkTotalCount;
    /** 返修率 = 返修单数 / 取镜总单数，百分比保留两位小数 */
    private String reworkRate;

    /** 明细行（行循环变量名 orders） */
    private List<WeeklyOrderRow> orders;
}
