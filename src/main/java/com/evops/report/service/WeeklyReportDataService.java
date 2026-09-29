package com.evops.report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.lens.entity.Lens;
import com.evops.lens.mapper.LensMapper;
import com.evops.order.entity.ProcessOrder;
import com.evops.order.entity.ProcessOrderStatusLog;
import com.evops.order.entity.ProcessRework;
import com.evops.order.mapper.ProcessOrderMapper;
import com.evops.order.mapper.ProcessOrderStatusLogMapper;
import com.evops.order.mapper.ProcessReworkMapper;
import com.evops.report.model.WeeklyOrderRow;
import com.evops.report.model.WeeklyReportData;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 周汇总数据装配：以"周期内取镜（PICKED_UP）的加工单"为口径。
 * <ul>
 *   <li>取镜日期取状态流转日志 PICKUP 行的业务时间（一单只取镜一次，去重保一条）；</li>
 *   <li>返修次数为该单截至导出时的累计返修登记数；返修率 = 有返修单数 / 取镜总单数；</li>
 *   <li>按门店分组，每组一份 {@link WeeklyReportData}；空门店不出文件。</li>
 * </ul>
 */
@Service
public class WeeklyReportDataService {

    /** 取镜流转操作类型（与 ProcessOrderService 写日志一致） */
    private static final String OP_PICKUP = "PICKUP";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Resource
    private ProcessOrderStatusLogMapper statusLogMapper;
    @Resource
    private ProcessOrderMapper orderMapper;
    @Resource
    private ProcessReworkMapper reworkMapper;
    @Resource
    private LensMapper lensMapper;

    /**
     * 按门店装配周汇总数据。
     *
     * @param start      周期起（含，按取镜业务时间）
     * @param end        周期止（含）
     * @param storeCodes 门店范围；null/空表示有取镜数据的全部门店
     * @return 按门店编码排序的报告数据，仅含至少一张取镜单的门店
     */
    public List<WeeklyReportData> build(LocalDate start, LocalDate end, List<String> storeCodes) {
        Set<String> storeFilter = storeCodes == null ? Collections.emptySet()
                : storeCodes.stream().map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());

        // 1) 周期内的取镜流转记录 → orderId（去重，保业务时间最早一条）
        LocalDateTime from = start.atStartOfDay();
        LocalDateTime to = end.plusDays(1).atStartOfDay();
        List<ProcessOrderStatusLog> pickups = statusLogMapper.selectList(
                new LambdaQueryWrapper<ProcessOrderStatusLog>()
                        .eq(ProcessOrderStatusLog::getOperType, OP_PICKUP)
                        .ge(ProcessOrderStatusLog::getBizTime, from)
                        .lt(ProcessOrderStatusLog::getBizTime, to)
                        .orderByAsc(ProcessOrderStatusLog::getBizTime)
                        .orderByAsc(ProcessOrderStatusLog::getId));
        Map<Long, ProcessOrderStatusLog> pickupByOrder = new LinkedHashMap<>();
        for (ProcessOrderStatusLog logRow : pickups) {
            pickupByOrder.putIfAbsent(logRow.getOrderId(), logRow);
        }
        if (pickupByOrder.isEmpty()) {
            return new ArrayList<>();
        }

        // 2) 主单 + 门店过滤
        List<ProcessOrder> orders = orderMapper.selectBatchIds(pickupByOrder.keySet());
        Map<Long, ProcessOrder> orderById = new HashMap<>();
        for (ProcessOrder o : orders) {
            if (storeFilter.isEmpty() || storeFilter.contains(o.getStoreCode())) {
                orderById.put(o.getId(), o);
            }
        }
        if (orderById.isEmpty()) {
            return new ArrayList<>();
        }

        // 3) 返修计数（截至导出时累计，不限周期）
        Map<Long, Long> reworkCountByOrder = new HashMap<>();
        List<ProcessRework> reworks = reworkMapper.selectList(
                new LambdaQueryWrapper<ProcessRework>()
                        .in(ProcessRework::getOrderId, orderById.keySet()));
        for (ProcessRework rw : reworks) {
            reworkCountByOrder.merge(rw.getOrderId(), 1L, Long::sum);
        }

        // 4) 镜款
        Set<Long> lensIds = orders.stream().map(ProcessOrder::getLensId).collect(Collectors.toSet());
        Map<Long, Lens> lensById = new HashMap<>();
        if (!lensIds.isEmpty()) {
            for (Lens lens : lensMapper.selectBatchIds(lensIds)) {
                lensById.put(lens.getId(), lens);
            }
        }

        // 5) 按门店分组装配
        Map<String, List<ProcessOrder>> byStore = orderById.values().stream()
                .collect(Collectors.groupingBy(
                        o -> o.getStoreCode() == null ? "" : o.getStoreCode(),
                        LinkedHashMap::new, Collectors.toList()));

        List<WeeklyReportData> result = new ArrayList<>();
        byStore.keySet().stream().sorted().forEach(store -> {
            List<ProcessOrder> storeOrders = byStore.get(store);
            // 明细按取镜时间、再按加工单号排序
            storeOrders.sort(Comparator
                    .comparing((ProcessOrder o) -> pickupByOrder.get(o.getId()).getBizTime(),
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(ProcessOrder::getOrderNo));

            WeeklyReportData data = new WeeklyReportData();
            data.setStoreCode(store);
            data.setPeriodStart(start.format(DATE));
            data.setPeriodEnd(end.format(DATE));
            data.setGeneratedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

            List<WeeklyOrderRow> rows = new ArrayList<>();
            int reworkOrderCount = 0;
            int reworkTotalCount = 0;
            for (ProcessOrder o : storeOrders) {
                int cnt = reworkCountByOrder.getOrDefault(o.getId(), 0L).intValue();
                if (cnt > 0) {
                    reworkOrderCount++;
                    reworkTotalCount += cnt;
                }
                Lens lens = lensById.get(o.getLensId());
                WeeklyOrderRow row = new WeeklyOrderRow();
                row.setOrderNo(o.getOrderNo());
                row.setStoreCode(o.getStoreCode());
                row.setLensName(lens == null ? "" : lens.getLensName());
                row.setLensCode(lens == null ? "" : lens.getLensCode());
                row.setOrderStatus(o.getOrderStatus());
                LocalDateTime pickupTime = pickupByOrder.get(o.getId()).getBizTime();
                row.setPickupDate(pickupTime == null ? "" : pickupTime.format(DATE));
                row.setReworkCount(cnt);
                row.setReworkFlag(cnt > 0 ? "是" : "否");
                rows.add(row);
            }
            data.setOrders(rows);
            data.setOrderCount(rows.size());
            data.setReworkOrderCount(reworkOrderCount);
            data.setReworkTotalCount(reworkTotalCount);
            data.setReworkRate(percent(reworkOrderCount, rows.size()));
            result.add(data);
        });
        return result;
    }

    /** 百分比保留两位小数，如 25.00%；无单返回 0.00% */
    static String percent(int numerator, int denominator) {
        if (denominator == 0) {
            return "0.00%";
        }
        double v = numerator * 100.0 / denominator;
        return String.format("%.2f%%", v);
    }
}
