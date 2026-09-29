package com.evops.report.service;

import com.evops.common.BizCode;
import com.evops.common.BizException;
import com.evops.common.RequestContext;
import com.evops.report.config.ReportProperties;
import com.evops.report.entity.ReportExportLog;
import com.evops.report.mapper.ReportExportLogMapper;
import com.evops.report.model.WeeklyReportData;
import com.evops.report.engine.WeeklyReportRenderer;
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
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 周汇总报告导出编排：周期校验 → 按门店装配 → 逐门店渲染 docx
 * → 单店直接返回 docx、多门店打包 zip；每次导出追加一行留痕。
 */
@Service
public class WeeklyReportExportService {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportExportService.class);
    public static final String TYPE_WEEKLY_ORDER = "WEEKLY_ORDER";
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Resource
    private WeeklyReportDataService dataService;
    @Resource
    private WeeklyReportRenderer renderer;
    @Resource
    private ReportProperties props;
    @Resource
    private ReportExportLogMapper exportLogMapper;

    public static class ExportResult {
        /** 单店=false 返回 docx；多店=true 返回 zip */
        private final boolean zip;
        private final String fileName;
        private final byte[] content;
        private final int fileCount;

        public ExportResult(boolean zip, String fileName, byte[] content, int fileCount) {
            this.zip = zip;
            this.fileName = fileName;
            this.content = content;
            this.fileCount = fileCount;
        }
        public boolean isZip() { return zip; }
        public String getFileName() { return fileName; }
        public byte[] getContent() { return content; }
        public int getFileCount() { return fileCount; }
    }

    /**
     * 导出。
     *
     * @param start      周期起（含）
     * @param end        周期止（含）
     * @param storeCodes 门店范围，null/空=有取镜数据的全部门店
     */
    public ExportResult export(LocalDate start, LocalDate end, List<String> storeCodes) {
        validatePeriod(start, end);

        List<String> normalized = normalizeStores(storeCodes);
        List<WeeklyReportData> reports = dataService.build(start, end, normalized);
        if (reports.isEmpty()) {
            throw new BizException(BizCode.REPORT_NO_DATA);
        }

        // 逐门店出独立文件
        List<RenderedFile> files = new ArrayList<>();
        for (WeeklyReportData data : reports) {
            byte[] docx = renderer.render(data);
            files.add(new RenderedFile(fileNameForStore(data.getStoreCode(), start, end), docx));
        }

        boolean zip = files.size() > 1;
        String exportNo = newExportNo();
        ExportResult result;
        if (zip) {
            byte[] zipBytes = zip(files);
            result = new ExportResult(true,
                    "加工单周汇总_" + start.format(BASIC_DATE) + "-" + end.format(BASIC_DATE) + ".zip",
                    zipBytes, files.size());
        } else {
            RenderedFile only = files.get(0);
            result = new ExportResult(false, only.getFileName(), only.getContent(), 1);
        }
        writeExportLog(exportNo, start, end, reports, files);
        log.info("周汇总报告导出完成 批次={} 周期={}~{} 门店数={} zip={}",
                exportNo, start, end, files.size(), zip);
        return result;
    }

    private void validatePeriod(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new BizException(BizCode.REPORT_PERIOD_INVALID, "统计周期起止不能为空");
        }
        if (end.isBefore(start)) {
            throw new BizException(BizCode.REPORT_PERIOD_INVALID, "统计周期止日不能早于起日");
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        if (days > props.getMaxPeriodDays()) {
            throw new BizException(BizCode.REPORT_PERIOD_INVALID,
                    "统计周期 " + days + " 天超过上限 " + props.getMaxPeriodDays() + " 天");
        }
    }

    private List<String> normalizeStores(List<String> storeCodes) {
        if (storeCodes == null) {
            return null;
        }
        Set<String> set = new LinkedHashSet<>();
        for (String s : storeCodes) {
            if (s != null && !s.trim().isEmpty()) {
                set.add(s.trim());
            }
        }
        return new ArrayList<>(set);
    }

    private String fileNameForStore(String store, LocalDate start, LocalDate end) {
        String safeStore = (store == null || store.isEmpty()) ? "UNKNOWN" : store;
        return "加工单周汇总_" + safeStore + "_" + start.format(BASIC_DATE) + "-" + end.format(BASIC_DATE) + ".docx";
    }

    private byte[] zip(List<RenderedFile> files) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        // 中文文件名：UTF-8 并设置 language bit，兼容常见解压软件
        try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            Set<String> used = new LinkedHashSet<>();
            for (RenderedFile f : files) {
                String entry = unique(used, f.getFileName());
                zos.putNextEntry(new ZipEntry(entry));
                zos.write(f.getContent());
                zos.closeEntry();
            }
        } catch (Exception ex) {
            throw new BizException(BizCode.SYSTEM_ERROR, "报告打包失败：" + ex.getMessage());
        }
        return bos.toByteArray();
    }

    private String unique(Set<String> used, String name) {
        if (used.add(name)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int i = 2;
        while (!used.add(base + "(" + i + ")" + ext)) {
            i++;
        }
        return base + "(" + i + ")" + ext;
    }

    private void writeExportLog(String exportNo, LocalDate start, LocalDate end,
                                List<WeeklyReportData> reports, List<RenderedFile> files) {
        ReportExportLog rec = new ReportExportLog();
        rec.setExportNo(exportNo);
        rec.setReportType(TYPE_WEEKLY_ORDER);
        rec.setPeriodStart(start);
        rec.setPeriodEnd(end);
        StringBuilder stores = new StringBuilder();
        for (int i = 0; i < reports.size(); i++) {
            if (i > 0) {
                stores.append(",");
            }
            stores.append(reports.get(i).getStoreCode());
        }
        rec.setStoreCodes(stores.toString());
        rec.setFileCount(files.size());
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) {
                names.append(",");
            }
            names.append(files.get(i).getFileName());
        }
        rec.setFileNames(truncate(names.toString(), 2000));
        exportLogMapper.insert(rec);
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }

    private String newExportNo() {
        String day = LocalDate.now().format(BASIC_DATE);
        String rand = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        return "RPT-" + day + "-" + rand;
    }
}
