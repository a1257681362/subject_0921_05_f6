package com.evops.report.service;

import com.evops.report.entity.WeeklyReportLog;
import com.evops.report.mapper.WeeklyReportLogMapper;
import com.evops.report.model.WeeklyReportFile;
import com.evops.report.model.WeeklyReportSnapshot;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 单店周报产出：聚合 + 渲染 docx + 留痕，放在独立事务里。
 * 批量按店导出时逐店调用——单店失败只回滚该店（留痕不写），不拖垮整批，
 * 与批量核对任务"逐单独立事务"同一思路。
 */
@Component
public class WeeklyReportStoreExporter {

    private static final DateTimeFormatter NAME_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Resource
    private WeeklyReportDataService dataService;
    @Resource
    private WeeklyReportTemplateRenderer renderer;
    @Resource
    private WeeklyReportLogMapper logMapper;

    /** 聚合、渲染、留痕同一独立事务；失败整体回滚（不留半截留痕） */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public WeeklyReportFile exportOne(String storeCode, LocalDate start, LocalDate end, String batchNo) {
        WeeklyReportSnapshot snapshot = dataService.buildSnapshot(storeCode, start, end);
        byte[] content = renderer.renderDocx(snapshot);
        String fileName = docxName(snapshot);
        writeLog(snapshot, fileName, batchNo);
        return new WeeklyReportFile(snapshot, fileName, content);
    }

    private String docxName(WeeklyReportSnapshot snapshot) {
        return "加工单周报_" + snapshot.getStoreCode() + "_"
                + snapshot.getPeriodStart().format(NAME_DATE) + "_"
                + snapshot.getPeriodEnd().format(NAME_DATE) + ".docx";
    }

    private void writeLog(WeeklyReportSnapshot snapshot, String fileName, String batchNo) {
        WeeklyReportLog entity = new WeeklyReportLog();
        entity.setStoreCode(snapshot.getStoreCode());
        entity.setPeriodStart(snapshot.getPeriodStart());
        entity.setPeriodEnd(snapshot.getPeriodEnd());
        entity.setTotalCount(snapshot.getTotalCount());
        entity.setReworkCount(snapshot.getReworkCount());
        entity.setReworkRate(snapshot.getReworkRate());
        entity.setTopLens(snapshot.getLensSummary());
        entity.setFileName(fileName);
        entity.setBatchNo(batchNo);
        logMapper.insert(entity);
    }
}
