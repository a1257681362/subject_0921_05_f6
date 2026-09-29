package com.evops.report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.change.entity.OrderChange;
import com.evops.change.mapper.OrderChangeMapper;
import com.evops.common.BizCode;
import com.evops.common.BizException;
import com.evops.common.LogMasks;
import com.evops.lens.entity.Lens;
import com.evops.lens.mapper.LensMapper;
import com.evops.order.entity.ProcessOrder;
import com.evops.order.mapper.ProcessOrderMapper;
import com.evops.order.service.ProcessOrderService;
import com.evops.report.config.ReportProperties;
import com.evops.report.model.WeeklyOrderRow;
import com.evops.report.model.WeeklyReportSnapshot;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 周报数据聚合。口径（总部质控统一）：
 * <ul>
 *   <li>统计周期按加工单建档时间（created_time）落 [periodStart 00:00, periodEnd 23:59:59]；</li>
 *   <li>只统计未冲销单（排除 CANCELLED）；</li>
 *   <li>返修单：周期内有"评审通过"变更单（改方/换镜款）关联的加工单，按加工单去重；</li>
 *   <li>返修率 = 返修单数 / 加工单总数；镜款分布按镜片名称计数降序。</li>
 * </ul>
 * 本类只产出中性快照，变量名/列名映射在渲染层按配置完成——模板变量改动不影响这里。
 */
@Service
public class WeeklyReportDataService {

    @Resource
    private ProcessOrderMapper orderMapper;
    @Resource
    private OrderChangeMapper changeMapper;
    @Resource
    private LensMapper lensMapper;
    @Resource
    private ReportProperties properties;

    /** 周期内有建档数据的门店（去重，按编号排序），供"全部门店批量导出" */
    public List<String> listStoresWithOrders(LocalDate start, LocalDate end) {
        validateRange(start, end);
        QueryWrapper<ProcessOrder> qw = new QueryWrapper<>();
        qw.select("DISTINCT store_code")
                .ge("created_time", start.atStartOfDay())
                .le("created_time", end.atTime(LocalTime.MAX))
                .ne("order_status", ProcessOrderService.CANCELLED)
                .isNotNull("store_code")
                .ne("store_code", "");
        List<Object> values = orderMapper.selectObjs(qw);
        List<String> stores = new ArrayList<>();
        for (Object v : values) {
            if (v != null && !v.toString().trim().isEmpty()) {
                stores.add(v.toString().trim());
            }
        }
        Collections.sort(stores);
        return stores;
    }

    /** 聚合一家门店的周报快照（无数据也返回空快照，便于按店出空表文件） */
    public WeeklyReportSnapshot buildSnapshot(String storeCode, LocalDate start, LocalDate end) {
        validateRange(start, end);
        if (storeCode == null || storeCode.trim().isEmpty()) {
            throw new BizException(BizCode.REPORT_STORE_REQUIRED);
        }
        String store = storeCode.trim();

        List<ProcessOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<ProcessOrder>()
                .eq(ProcessOrder::getStoreCode, store)
                .ne(ProcessOrder::getOrderStatus, ProcessOrderService.CANCELLED)
                .ge(ProcessOrder::getCreatedTime, start.atStartOfDay())
                .le(ProcessOrder::getCreatedTime, end.atTime(LocalTime.MAX))
                .orderByAsc(ProcessOrder::getId));

        // 返修集合：周期内评审通过的变更单关联的加工单号
        List<OrderChange> approved = changeMapper.selectList(new LambdaQueryWrapper<OrderChange>()
                .eq(OrderChange::getStoreCode, store)
                .eq(OrderChange::getReviewStatus, OrderChange.APPROVED)
                .ge(OrderChange::getReviewedTime, start.atStartOfDay())
                .le(OrderChange::getReviewedTime, end.atTime(LocalTime.MAX)));
        Set<String> reworkOrderNos = approved.stream()
                .map(OrderChange::getOrderNo)
                .collect(Collectors.toCollection(HashSet::new));

        Map<Long, String> lensNames = loadLensNames(orders);
        ReportProperties.Labels labels = properties.getLabels();

        List<WeeklyOrderRow> rows = new ArrayList<>();
        Map<String, Integer> lensCount = new HashMap<>();
        int reworkCount = 0;
        for (ProcessOrder order : orders) {
            WeeklyOrderRow row = new WeeklyOrderRow();
            row.setOrderNo(order.getOrderNo());
            String lensName = lensNames.getOrDefault(order.getLensId(), "");
            row.setLensName(lensName);
            row.setOrderDate(toDate(order.getCreatedTime()));
            row.setStatusText(statusText(order.getOrderStatus(), labels));
            row.setCustomer(LogMasks.name(order.getCustomerName()));
            boolean rework = reworkOrderNos.contains(order.getOrderNo());
            row.setRework(rework);
            row.setReworkText(rework ? labels.getReworkYes() : labels.getReworkNo());
            if (rework) {
                reworkCount++;
            }
            rows.add(row);
            lensCount.merge(lensName, 1, Integer::sum);
        }

        WeeklyReportSnapshot snapshot = new WeeklyReportSnapshot();
        snapshot.setStoreCode(store);
        snapshot.setPeriodStart(start);
        snapshot.setPeriodEnd(end);
        snapshot.setGeneratedAt(LocalDateTime.now());
        snapshot.setTotalCount(orders.size());
        snapshot.setReworkCount(reworkCount);
        snapshot.setReworkRate(formatRate(reworkCount, orders.size()));
        snapshot.setLensSummary(formatLensSummary(lensCount));
        snapshot.setRows(rows);
        return snapshot;
    }

    private Map<Long, String> loadLensNames(List<ProcessOrder> orders) {
        if (orders.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> lensIds = orders.stream()
                .map(ProcessOrder::getLensId)
                .collect(Collectors.toSet());
        List<Lens> lenses = lensMapper.selectBatchIds(lensIds);
        Map<Long, String> map = new HashMap<>();
        for (Lens lens : lenses) {
            map.put(lens.getId(), lens.getLensName() == null ? "" : lens.getLensName());
        }
        return map;
    }

    /** 镜款分布：数量降序、同量按名称，输出"镜款×数量"以中文分号连接 */
    private String formatLensSummary(Map<String, Integer> lensCount) {
        return lensCount.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(e -> e.getKey() + "×" + e.getValue())
                .collect(Collectors.joining("；"));
    }

    private String formatRate(int rework, int total) {
        if (total == 0) {
            return "0.00%";
        }
        double rate = rework * 100.0 / total;
        return String.format("%.2f%%", rate);
    }

    private String toDate(LocalDateTime time) {
        return time == null ? "" : time.toLocalDate().toString();
    }

    private String statusText(String status, ReportProperties.Labels labels) {
        if (status == null) {
            return "";
        }
        switch (status) {
            case "CREATED": return labels.getStatusCreated();
            case "PROCESSING": return labels.getStatusProcessing();
            case "READY": return labels.getStatusReady();
            case "PICKED_UP": return labels.getStatusPickedUp();
            case "CANCELLED": return labels.getStatusCancelled();
            default: return status;
        }
    }

    private void validateRange(LocalDate start, LocalDate end) {
        if (start == null || end == null || end.isBefore(start)) {
            throw new BizException(BizCode.REPORT_DATE_RANGE_INVALID);
        }
    }
}
