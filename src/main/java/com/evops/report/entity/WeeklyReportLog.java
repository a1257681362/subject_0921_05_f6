package com.evops.report.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 周报生成留痕（只追加）：验光师每次按门店导出加工单汇总周报写一行。
 * 底账数字以加工单/变更单实时汇总为准，本表只记录"谁在何时导过哪份周报"及当时快照。
 * 批量按店导出时多条记录共享一个 batchNo。
 */
@Getter
@Setter
@TableName("t_weekly_report_log")
public class WeeklyReportLog extends BaseEntity {

    private String storeCode;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    /** 周期内加工单总数（未冲销） */
    private Integer totalCount;
    /** 返修单数（周期内评审通过变更单关联的加工单去重数） */
    private Integer reworkCount;
    /** 返修率文本快照，如 12.50% */
    private String reworkRate;
    /** 镜款分布快照，如 1.60非球面×8；1.67非球面×3 */
    private String topLens;
    /** 产出条目文件名（zip 内 docx 条目名） */
    private String fileName;
    /** 批量批次号：一次批量按店导出共享一个批次号；单店导出同样生成 */
    private String batchNo;
}
