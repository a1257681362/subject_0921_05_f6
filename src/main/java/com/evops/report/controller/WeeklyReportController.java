package com.evops.report.controller;

import com.evops.common.ApiResponse;
import com.evops.report.engine.WeeklyReportRenderer;
import com.evops.report.service.WeeklyReportExportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 加工单周汇总报告接口。
 * 导出按取镜周期与门店：单店直接回 docx；多店（或不传门店=全部）打包 zip（每家门店一个 docx）。
 * 报告为只读统计 + 导出留痕；模板可外部 file: 化并热重载（换变量/列不改代码）。
 */
@RestController
@RequestMapping("/api/reports")
public class WeeklyReportController {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportController.class);
    private static final MediaType DOCX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private static final MediaType ZIP = MediaType.parseMediaType("application/zip");

    private final WeeklyReportExportService exportService;
    private final WeeklyReportRenderer renderer;

    public WeeklyReportController(WeeklyReportExportService exportService,
                                  WeeklyReportRenderer renderer) {
        this.exportService = exportService;
        this.renderer = renderer;
    }

    /**
     * 导出周汇总。
     *
     * @param start     周期起 yyyy-MM-dd（含，按取镜时间）
     * @param end       周期止 yyyy-MM-dd（含）
     * @param storeCode 门店编码，可重复传多个；不传=有取镜数据的全部门店
     */
    @GetMapping("/weekly-orders")
    public ResponseEntity<ByteArrayResource> exportWeekly(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(required = false) List<String> storeCode) {

        WeeklyReportExportService.ExportResult result = exportService.export(start, end, storeCode);
        MediaType mediaType = result.isZip() ? ZIP : DOCX;
        String fallback = result.isZip() ? "weekly-report.zip" : "weekly-report.docx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(result.getFileName(), fallback))
                .contentType(mediaType)
                .contentLength(result.getContent().length)
                .body(new ByteArrayResource(result.getContent()));
    }

    /** 立即重载模板（file: 外部模板改完后可主动触发；平时也按 mtime 自动热更） */
    @PostMapping("/weekly-template/reload")
    public ApiResponse<Map<String, Object>> reloadTemplate() {
        renderer.reloadTemplate();
        return ApiResponse.ok(templateInfo());
    }

    /** 模板当前状态（路径、隔行底色配置） */
    @GetMapping("/weekly-template/info")
    public ApiResponse<Map<String, Object>> templateInfoEndpoint() {
        return ApiResponse.ok(templateInfo());
    }

    private Map<String, Object> templateInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("templatePath", renderer.getProps().getWeeklyTemplatePath());
        info.put("zebraStripe", renderer.getProps().isZebraStripe());
        info.put("zebraColor", renderer.getProps().getZebraColor());
        return info;
    }

    /** RFC 5987 文件名编码：ASCII 回退 + UTF-8 filename*，兼容中文文件名 */
    private String contentDisposition(String fileName, String fallback) {
        try {
            String encoded = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20");
            return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
        } catch (UnsupportedEncodingException ex) {
            return "attachment; filename=\"" + fallback + "\"";
        }
    }
}
