package com.evops.report.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 报告导出留痕（只追加）：每次周汇总报告导出（单店 docx / 批量 zip）记一行，
 * 不存文件内容，仅记周期、门店范围、文件清单，便于追溯。
 */
@Getter
@Setter
@TableName("t_report_export_log")
public class ReportExportLog extends BaseEntity {
    /** 导出批次号 RPT-yyyyMMdd-XXXXXXXXXX */
    private String exportNo;
    /** WEEKLY_ORDER 加工单周汇总 */
    private String reportType;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    /** 本次导出的门店编码（逗号分隔；null=全部门店） */
    private String storeCodes;
    private Integer fileCount;
    /** 产出文件名清单（逗号分隔） */
    private String fileNames;
}
