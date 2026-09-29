package com.evops.report.service;

import com.evops.report.model.WeeklyReportFile;
import com.evops.report.model.WeeklyReportSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 周报导出外观：单店出 docx、批量按店各出一个 docx 打 ZIP、JSON 预览。
 * 每家店的聚合/渲染/留痕在 {@link WeeklyReportStoreExporter} 的独立事务中完成，
 * 批量时单店失败只跳过该店，其余门店照常产出。
 */
@Service
public class WeeklyReportService {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportService.class);

    private static final DateTimeFormatter NAME_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Resource
    private WeeklyReportDataService dataService;
    @Resource
    private WeeklyReportStoreExporter storeExporter;
    @Resource
    private com.evops.report.config.ReportProperties properties;

    /** 单店周报 docx（独立事务：渲染与留痕同生共死） */
    public WeeklyReportFile generateOne(String storeCode, LocalDate start, LocalDate end) {
        WeeklyReportFile file = storeExporter.exportOne(storeCode, start, end, null);
        log.info("周报已生成 门店={} 周期={}~{} 单数={} 返修率={}",
                storeCode, start, end, file.getSnapshot().getTotalCount(),
                file.getSnapshot().getReworkRate());
        return file;
    }

    /** 单店周报数据预览（不落盘、不留痕），供前端先核口径再导出 */
    public WeeklyReportSnapshot preview(String storeCode, LocalDate start, LocalDate end) {
        return dataService.buildSnapshot(storeCode, start, end);
    }

    /** 批量门店口径预览（不渲染、不留痕）：stores 空 = 周期内有单的全部门店 */
    public List<WeeklyReportSnapshot> previewStores(List<String> stores, LocalDate start, LocalDate end) {
        List<WeeklyReportSnapshot> result = new ArrayList<>();
        for (String store : resolveStores(stores, start, end)) {
            result.add(dataService.buildSnapshot(store, start, end));
        }
        return result;
    }

    /**
     * 批量按店各出一个 docx 并打 ZIP。
     * stores 为空 = 周期内有建档数据的全部门店（去重排序）。
     * 每店独立事务，单店失败只跳过并记录，不影响其他门店。
     */
    public BatchResult generateBatch(List<String> stores, LocalDate start, LocalDate end) {
        List<String> targetStores = resolveStores(stores, start, end);
        String batchNo = "RPT-" + LocalDate.now().format(NAME_DATE) + "-" + Long.toHexString(System.nanoTime());

        List<WeeklyReportFile> files = new ArrayList<>();
        List<String> failedStores = new ArrayList<>();
        for (String store : targetStores) {
            try {
                files.add(storeExporter.exportOne(store, start, end, batchNo));
            } catch (Exception ex) {
                // 单店失败不拖垮整批：该店独立事务已回滚（不留痕），继续其余门店
                log.warn("批量周报单店生成失败，已跳过 门店={} 批次={} 原因={}", store, batchNo, ex.getMessage());
                failedStores.add(store);
            }
        }

        byte[] zip = toZip(files);
        String zipName = "加工单周报批量_" + start.format(NAME_DATE) + "_" + end.format(NAME_DATE)
                + "_" + batchNo + ".zip";
        log.info("批量周报已生成 批次={} 门店数={} 成功={} 跳过={}",
                batchNo, targetStores.size(), files.size(), failedStores.size());
        return new BatchResult(batchNo, zipName, zip, files, failedStores);
    }

    private List<String> resolveStores(List<String> stores, LocalDate start, LocalDate end) {
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        if (stores == null || stores.isEmpty()) {
            resolved.addAll(dataService.listStoresWithOrders(start, end));
        } else {
            for (String s : stores) {
                if (s != null && !s.trim().isEmpty()) {
                    resolved.add(s.trim());
                }
            }
        }
        List<String> result = new ArrayList<>(resolved);
        int limit = properties.getBatchStoreLimit();
        if (limit > 0 && result.size() > limit) {
            throw new com.evops.common.BizException(com.evops.common.BizCode.VALIDATION_FAILED,
                    "单次批量导出门店数不得超过 " + limit + "，当前 " + result.size());
        }
        return result;
    }

    private byte[] toZip(List<WeeklyReportFile> files) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            for (WeeklyReportFile file : files) {
                zos.putNextEntry(new ZipEntry(file.getFileName()));
                zos.write(file.getContent());
                zos.closeEntry();
            }
        } catch (Exception ex) {
            throw new IllegalStateException("批量周报打包失败：" + ex.getMessage(), ex);
        }
        return bos.toByteArray();
    }

    /** 批量结果：ZIP 字节与各店条目（供返回清单） */
    public static class BatchResult {
        private final String batchNo;
        private final String zipName;
        private final byte[] zipContent;
        private final List<WeeklyReportFile> files;
        private final List<String> failedStores;

        public BatchResult(String batchNo, String zipName, byte[] zipContent,
                           List<WeeklyReportFile> files, List<String> failedStores) {
            this.batchNo = batchNo;
            this.zipName = zipName;
            this.zipContent = zipContent;
            this.files = files;
            this.failedStores = failedStores;
        }

        public String getBatchNo() { return batchNo; }
        public String getZipName() { return zipName; }
        public byte[] getZipContent() { return zipContent; }
        public List<WeeklyReportFile> getFiles() { return files; }
        public List<String> getFailedStores() { return failedStores; }
    }
}
