package com.evops.report.model;

import lombok.Getter;
import lombok.Setter;

/**
 * 周报明细行：一张加工单一行。
 * 字段值已是报告展示口径（状态中文、客户打码、是否返修文本），
 * 与模板列标签的映射在渲染层按配置完成，模板列改名不动这里。
 */
@Getter
@Setter
public class WeeklyOrderRow {
    /** 加工单号 */
    private String orderNo;
    /** 镜款（镜片名称） */
    private String lensName;
    /** 开单日期 yyyy-MM-dd */
    private String orderDate;
    /** 状态中文 */
    private String statusText;
    /** 客户（打码，仅首字） */
    private String customer;
    /** 是否返修（周期内有评审通过的变更单） */
    private boolean rework;
    /** 是否返修的展示文本（返修 / —），预览与 Word 同口径 */
    private String reworkText;
}
