package com.evops.order.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 加工单返修记录（只追加）。客户取镜（PICKED_UP）后返修，每次登记一行；
 * 加工单状态保持 PICKED_UP 不变，不走进度状态机。
 * 周汇总报告按本表统计返修单数/返修率。
 */
@Getter
@Setter
@TableName("t_process_rework")
public class ProcessRework extends BaseEntity {
    /** 返修单号 RW-yyyyMMdd-XXXXXXXXXX */
    private String reworkNo;
    private Long orderId;
    /** 加工单号（冗余，便于报告聚合） */
    private String orderNo;
    private String reworkReason;
}
