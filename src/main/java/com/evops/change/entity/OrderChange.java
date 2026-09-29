package com.evops.change.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 加工单变更单（只追加）：验光师对已建档加工单提出改方/换镜款，先评审后落库。
 * reviewStatus：PENDING 待评审 / APPROVED 通过 / REJECTED 驳回，终态不可再变（有新意见另开新单）。
 * 变更单本身不改加工单；通过后验光师仍走既有"修改加工单"接口，写路径维持不变。
 */
@Getter
@Setter
@TableName("t_order_change")
public class OrderChange extends BaseEntity {

    public static final String TYPE_REWRITE = "REWRITE"; // 改方（重配度数）
    public static final String TYPE_ALTER = "ALTER";     // 换镜款（变更镜片）

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    /** 变更单号，全库唯一 */
    private String changeNo;
    /** 关联加工单号 */
    private String orderNo;
    /** 提交时所在门店（冗余便于按店汇总） */
    private String storeCode;
    /** REWRITE 改方 / ALTER 换镜款 */
    private String changeType;
    /** 变更原因 */
    private String reason;
    /** PENDING / APPROVED / REJECTED */
    private String reviewStatus;
    /** 安排的验光师评审时间 */
    private LocalDateTime reviewTime;
    /** 评审人 */
    private Long reviewerId;
    /** 评审意见 */
    private String reviewComment;
    /** 实际评审完成时间 */
    private LocalDateTime reviewedTime;
}
