package com.evops.report.controller;

import com.evops.common.ApiResponse;
import com.evops.report.dto.WeeklyBatchRequest;
import com.evops.report.model.WeeklyReportFile;
import com.evops.report.model.WeeklyReportSnapshot;
import com.evops.report.service.WeeklyReportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 验光师加工单汇总周报（poi-tl Word 模板）。
 * <ul>
 *   <li>GET  preview：先看 JSON 口径（加工单总数、返修率、镜款分布、明细），不留痕；</li>
 *   <li>GET  docx：单店出一个 Word 文件；</li>
 *   <li>POST batch：按门店集合各出一个 Word 打 ZIP（storeCodes 空 = 全部门店）。</li>
 * </ul>
 * 写（导出）接口同样携带 X-Operator-Id / X-Request-No / X-Biz-Time 审计头，每次按店留痕。
 */
@RestController
@RequestMapping("/api/reports/weekly")
public class WeeklyReportController {

    private static final MediaType DOCX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private static final MediaType ZIP = MediaType.parseMediaType("application/zip");

    private final WeeklyReportService reportService;

    public WeeklyReportController(WeeklyReportService reportService) {
        this.reportService = reportService;
    }

    /** JSON 预览：不生成文件、不留痕，供验光师导出前核对口径 */
    @GetMapping("/preview")
    public ApiResponse<WeeklyReportSnapshot> preview(@RequestParam(required = false) String storeCode,
                                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                     LocalDate periodStart,
                                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                     LocalDate periodEnd) {
        return ApiResponse.ok(reportService.preview(storeCode, periodStart, periodEnd));
    }

    /** 单店周报 Word 下载 */
    @GetMapping("/docx")
    public ResponseEntity<byte[]> docx(@RequestParam(required = false) String storeCode,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                       LocalDate periodStart,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                       LocalDate periodEnd) {
        WeeklyReportFile file = reportService.generateOne(storeCode, periodStart, periodEnd);
        return ResponseEntity.ok()
                .headers(downloadHeaders(file.getFileName(), DOCX))
                .body(file.getContent());
    }

    /**
     * 批量按店出文件：body 传门店集合（空 = 周期内有单的全部门店），
     * 返回 ZIP（每家门店一个 docx）。批次号在响应头 X-Batch-No；跳过门店在 X-Failed-Stores。
     */
    @PostMapping("/batch")
    public ResponseEntity<byte[]> batch(@Valid @RequestBody WeeklyBatchRequest request) {
        WeeklyReportService.BatchResult result = reportService.generateBatch(
                request.getStoreCodes(), request.getPeriodStart(), request.getPeriodEnd());
        HttpHeaders headers = downloadHeaders(result.getZipName(), ZIP);
        headers.add("X-Batch-No", result.getBatchNo());
        headers.add("X-Failed-Stores", String.join(",", result.getFailedStores()));
        // 便于调用方知晓每店条目（文件名），用纯 ASCII 列表
        headers.add("X-File-Count", String.valueOf(result.getFiles().size()));
        return ResponseEntity.ok().headers(headers).body(result.getZipContent());
    }

    /** 批量门店清单预览：返回各店文件名与口径要点，不落盘不留痕 */
    @PostMapping("/batch/preview")
    public ApiResponse<List<StoreSummary>> batchPreview(@Valid @RequestBody WeeklyBatchRequest request) {
        // 复用 docx 生成会留痕，预览改为只取快照
        List<StoreSummary> summaries = reportService.previewStores(
                request.getStoreCodes(), request.getPeriodStart(), request.getPeriodEnd()).stream()
                .map(StoreSummary::from)
                .collect(Collectors.toList());
        return ApiResponse.ok(summaries);
    }

    private HttpHeaders downloadHeaders(String fileName, MediaType mediaType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(fileName));
        return headers;
    }

    /** 中文文件名：filename 给 ASCII 兜底，filename* 给 UTF-8 原文 */
    private String contentDisposition(String fileName) {
        try {
            String encoded = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20");
            return "attachment; filename=\"report.bin\"; filename*=UTF-8''" + encoded;
        } catch (UnsupportedEncodingException ex) {
            return "attachment; filename=\"report.bin\"";
        }
    }

    /** 批量预览的每店摘要 */
    public static class StoreSummary {
        private String storeCode;
        private int totalCount;
        private int reworkCount;
        private String reworkRate;
        private String lensSummary;

        static StoreSummary from(WeeklyReportSnapshot s) {
            StoreSummary summary = new StoreSummary();
            summary.storeCode = s.getStoreCode();
            summary.totalCount = s.getTotalCount();
            summary.reworkCount = s.getReworkCount();
            summary.reworkRate = s.getReworkRate();
            summary.lensSummary = s.getLensSummary();
            return summary;
        }

        public String getStoreCode() { return storeCode; }
        public int getTotalCount() { return totalCount; }
        public int getReworkCount() { return reworkCount; }
        public String getReworkRate() { return reworkRate; }
        public String getLensSummary() { return lensSummary; }
    }
}
