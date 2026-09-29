package com.evops.report.model;

import lombok.Getter;
import lombok.Setter;

/**
 * 周汇总明细表单行（加工单）。字段名即模板中的变量名（[orderNo]/[lensName]…），
 * 模板增删列、改变量只改 docx，不改代码——数据里没有的变量 poi-tl 默认清空，不报错。
 */
@Getter
@Setter
public class WeeklyOrderRow {
    /** 加工单号 */
    private String orderNo;
    /** 门店编码 */
    private String storeCode;
    /** 镜款（镜片名称） */
    private String lensName;
    /** 镜片编码 */
    private String lensCode;
    /** 当前状态 */
    private String orderStatus;
    /** 取镜日期 yyyy-MM-dd */
    private String pickupDate;
    /** 返修次数（截至导出时累计） */
    private int reworkCount;
    /** 是否返修：是/否 */
    private String reworkFlag;
}
