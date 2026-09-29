package com.evops.report.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 周汇总报告配置（evops.report.*）：
 * 模板路径（classpath: 随包默认；生产可改 file:/path/xxx.docx 外部化）、
 * 明细表隔行底色开关与颜色、明细表在文档中的序号、周期上限天数。
 */
@Component
public class ReportProperties {

    /** 加工单周汇总 Word 模板路径（poi-tl） */
    private final String weeklyTemplatePath;
    /** 明细表是否隔行底色 */
    private final boolean zebraStripe;
    /** 隔行底色（十六进制 RGB，不含 #） */
    private final String zebraColor;
    /** 明细表在文档中的序号（从 0 开始；默认模板只有一张表） */
    private final int detailTableIndex;
    /** 单次导出统计周期上限（天），防止误拉全量 */
    private final int maxPeriodDays;

    public ReportProperties(
            @Value("${evops.report.weekly-template-path:classpath:templates/weekly-orders.docx}")
            String weeklyTemplatePath,
            @Value("${evops.report.zebra-stripe:true}") boolean zebraStripe,
            @Value("${evops.report.zebra-color:F2F2F2}") String zebraColor,
            @Value("${evops.report.detail-table-index:0}") int detailTableIndex,
            @Value("${evops.report.max-period-days:93}") int maxPeriodDays) {
        this.weeklyTemplatePath = weeklyTemplatePath;
        this.zebraStripe = zebraStripe;
        this.zebraColor = zebraColor;
        this.detailTableIndex = detailTableIndex;
        this.maxPeriodDays = maxPeriodDays;
    }

    public String getWeeklyTemplatePath() { return weeklyTemplatePath; }
    public boolean isZebraStripe() { return zebraStripe; }
    public String getZebraColor() { return zebraColor; }
    public int getDetailTableIndex() { return detailTableIndex; }
    public int getMaxPeriodDays() { return maxPeriodDays; }
}
