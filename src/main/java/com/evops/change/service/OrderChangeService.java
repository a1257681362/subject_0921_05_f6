package com.evops.change.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.change.dto.ChangeCreateRequest;
import com.evops.change.dto.ChangeReviewRequest;
import com.evops.change.dto.ChangeScheduleRequest;
import com.evops.change.entity.OrderChange;
import com.evops.change.mapper.OrderChangeMapper;
import com.evops.common.BizCode;
import com.evops.common.BizException;
import com.evops.common.LogMasks;
import com.evops.common.RequestContext;
import com.evops.order.entity.ProcessOrder;
import com.evops.order.mapper.ProcessOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 加工单变更单：验光师提交（改方/换镜款）→ 安排评审时间 → 评审通过/驳回，全程只追加留痕。
 * 变更单不直接改加工单；既有加工单建档/修改/状态流转写路径一律不变，
 * 评审通过后由验光师按原"修改加工单"流程落库。
 * 重复提交由 uk_order_change_request 兜底，每单至多一张待评审变更单由 uk_order_change_pending 兜底。
 */
@Service
public class OrderChangeService {

    private static final Logger log = LoggerFactory.getLogger(OrderChangeService.class);

    private static final Set<String> CHANGE_TYPES =
            new HashSet<>(Arrays.asList(OrderChange.TYPE_REWRITE, OrderChange.TYPE_ALTER));

    @Resource
    private OrderChangeMapper changeMapper;
    @Resource
    private ProcessOrderMapper orderMapper;

    /** 提交变更单：加工单必须存在；同一加工单已有待评审变更单时拒绝 */
    @Transactional(rollbackFor = Exception.class)
    public OrderChange submit(ChangeCreateRequest req) {
        String changeType = req.getChangeType() == null || req.getChangeType().trim().isEmpty()
                ? OrderChange.TYPE_REWRITE : req.getChangeType().trim().toUpperCase();
        if (!CHANGE_TYPES.contains(changeType)) {
            throw new BizException(BizCode.VALIDATION_FAILED, "变更类型只能为 REWRITE（改方）或 ALTER（换镜款）");
        }

        ProcessOrder order = orderMapper.selectOne(new LambdaQueryWrapper<ProcessOrder>()
                .eq(ProcessOrder::getOrderNo, req.getOrderNo().trim()));
        if (order == null) {
            throw new BizException(BizCode.ORDER_NOT_FOUND);
        }

        // 业务层先拦一道，库层 uk_order_change_pending 兜底并发
        Long pending = changeMapper.selectCount(new LambdaQueryWrapper<OrderChange>()
                .eq(OrderChange::getOrderNo, order.getOrderNo())
                .eq(OrderChange::getReviewStatus, OrderChange.PENDING));
        if (pending != null && pending > 0) {
            throw new BizException(BizCode.ORDER_CHANGE_ALREADY_PENDING);
        }

        String storeCode = req.getStoreCode() == null || req.getStoreCode().trim().isEmpty()
                ? order.getStoreCode() : req.getStoreCode().trim();

        OrderChange change = new OrderChange();
        change.setChangeNo(req.getChangeNo().trim());
        change.setOrderNo(order.getOrderNo());
        change.setStoreCode(storeCode);
        change.setChangeType(changeType);
        change.setReason(req.getReason().trim());
        change.setReviewStatus(OrderChange.PENDING);
        changeMapper.insert(change);

        change = changeMapper.selectById(change.getId());
        log.info("变更单提交 changeNo={} orderNo={} 门店={} 类型={} 原因={}",
                change.getChangeNo(), change.getOrderNo(), change.getStoreCode(), changeType,
                LogMasks.singleLine(change.getReason()));
        return change;
    }

    /** 安排验光师评审时间：仅待评审单可安排，可改期 */
    @Transactional(rollbackFor = Exception.class)
    public OrderChange schedule(String changeNo, ChangeScheduleRequest req) {
        OrderChange change = requireChange(changeNo);
        requirePending(change);
        if (req.getReviewTime().isBefore(RequestContext.bizTime())) {
            throw new BizException(BizCode.VALIDATION_FAILED, "评审时间不能早于当前业务时间");
        }
        change.setReviewTime(req.getReviewTime());
        if (changeMapper.updateById(change) == 0) {
            throw new BizException(BizCode.VERSION_CONFLICT);
        }
        change = changeMapper.selectById(change.getId());
        log.info("变更单评审安排 changeNo={} orderNo={} 评审时间={}",
                change.getChangeNo(), change.getOrderNo(), change.getReviewTime());
        return change;
    }

    /** 录入评审结论：须先安排评审时间；通过/驳回均为终态，只追加不回改 */
    @Transactional(rollbackFor = Exception.class)
    public OrderChange review(String changeNo, ChangeReviewRequest req) {
        OrderChange change = requireChange(changeNo);
        requirePending(change);
        if (change.getReviewTime() == null) {
            throw new BizException(BizCode.ORDER_CHANGE_NOT_SCHEDULED);
        }
        Long reviewerId = req.getReviewerId() == null ? RequestContext.operatorId() : req.getReviewerId();
        String comment = req.getReviewComment() == null ? null : req.getReviewComment().trim();

        change.setReviewStatus(Boolean.TRUE.equals(req.getApproved())
                ? OrderChange.APPROVED : OrderChange.REJECTED);
        change.setReviewerId(reviewerId);
        change.setReviewComment(comment);
        change.setReviewedTime(RequestContext.bizTime());
        if (changeMapper.updateById(change) == 0) {
            throw new BizException(BizCode.VERSION_CONFLICT);
        }
        change = changeMapper.selectById(change.getId());
        log.info("变更单评审完成 changeNo={} orderNo={} 结论={} 评审人={}",
                change.getChangeNo(), change.getOrderNo(), change.getReviewStatus(), reviewerId);
        return change;
    }

    public OrderChange getByChangeNo(String changeNo) {
        return requireChange(changeNo);
    }

    public List<OrderChange> list(String status, String orderNo, String storeCode) {
        LambdaQueryWrapper<OrderChange> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.trim().isEmpty()) {
            qw.eq(OrderChange::getReviewStatus, status.trim().toUpperCase());
        }
        if (orderNo != null && !orderNo.trim().isEmpty()) {
            qw.eq(OrderChange::getOrderNo, orderNo.trim());
        }
        if (storeCode != null && !storeCode.trim().isEmpty()) {
            qw.eq(OrderChange::getStoreCode, storeCode.trim());
        }
        qw.orderByDesc(OrderChange::getId);
        return changeMapper.selectList(qw);
    }

    private OrderChange requireChange(String changeNo) {
        if (changeNo == null || changeNo.trim().isEmpty()) {
            throw new BizException(BizCode.ORDER_CHANGE_NOT_FOUND);
        }
        OrderChange change = changeMapper.selectOne(new LambdaQueryWrapper<OrderChange>()
                .eq(OrderChange::getChangeNo, changeNo.trim()));
        if (change == null) {
            throw new BizException(BizCode.ORDER_CHANGE_NOT_FOUND);
        }
        return change;
    }

    private void requirePending(OrderChange change) {
        if (!OrderChange.PENDING.equals(change.getReviewStatus())) {
            throw new BizException(BizCode.ORDER_CHANGE_STATUS_ILLEGAL,
                    "仅待评审的变更单可操作，当前状态[" + change.getReviewStatus() + "]");
        }
    }
}
