package com.evops.report.engine;

import com.evops.report.config.ReportProperties;
import com.evops.report.model.WeeklyOrderRow;
import com.evops.report.model.WeeklyReportData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渲染器单测，聚焦两条硬需求：
 * 1) 模板增删变量/明细列 <b>不改代码</b>：模板里出现数据中没有的变量、或列结构与默认不同，
 *    渲染照常、未知变量渲染为空；
 * 2) file: 外部模板改动后按 mtime 自动热加载，无需重启。
 */
class WeeklyReportRendererTest {

    private WeeklyReportRenderer newRenderer(File template) {
        ReportProperties props = new ReportProperties(
                "file:" + template.getAbsolutePath(), true, "F2F2F2", 0, 93);
        return new WeeklyReportRenderer(new DefaultResourceLoader(), props, new ObjectMapper());
    }

    /** 与默认模板列结构不同的外部模板：3 列（单号/镜款/一个数据中不存在的"自定义列"），抬头多加一个未知变量 */
    private File writeTemplate(File file, String headExtra) throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText(
                    "店={{storeCode}} 周期={{periodStart}}~{{periodEnd}}" + headExtra);
            XWPFTable t = doc.createTable(2, 3);
            String[] heads = {"加工单号", "镜款", "自定义列"};
            for (int i = 0; i < 3; i++) {
                t.getRow(0).getCell(i).setText(heads[i]);
            }
            XWPFTableRow r = t.getRow(1);
            r.getCell(0).setText("{{orders}}[orderNo]");
            r.getCell(1).setText("[lensName]");
            r.getCell(2).setText("[customCol]"); // WeeklyOrderRow 无此字段
            Files.createDirectories(file.getParentFile().toPath());
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                doc.write(fos);
            }
        }
        return file;
    }

    private WeeklyReportData sampleData() {
        WeeklyReportData d = new WeeklyReportData();
        d.setStoreCode("S01");
        d.setPeriodStart("2026-09-21");
        d.setPeriodEnd("2026-09-27");
        d.setGeneratedAt("2026-09-29 10:00");
        d.setOrderCount(2);
        d.setReworkOrderCount(0);
        d.setReworkTotalCount(0);
        d.setReworkRate("0.00%");
        List<WeeklyOrderRow> rows = new ArrayList<>();
        for (String no : Arrays.asList("O-1", "O-2")) {
            WeeklyOrderRow row = new WeeklyOrderRow();
            row.setOrderNo(no);
            row.setLensName("镜款" + no);
            row.setReworkCount(0);
            row.setReworkFlag("否");
            rows.add(row);
        }
        d.setOrders(rows);
        return d;
    }

    @Test
    void template_with_extra_unknown_variables_renders_without_code_change() throws Exception {
        File tpl = writeTemplate(new File("target/test-templates/extra-vars.docx"),
                " 店长={{storeManager}}"); // storeManager 数据中不存在
        WeeklyReportRenderer renderer = newRenderer(tpl);
        renderer.init();

        byte[] out = renderer.render(sampleData());
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(out))) {
            String head = doc.getParagraphs().get(0).getText();
            assertTrue(head.contains("S01"), "已知变量应渲染");
            assertFalse(head.contains("{{"), "未知变量应清空，不残留标签");
            assertTrue(head.contains("店长="), "未知变量位置保留版式、值为空");

            XWPFTable t = doc.getTables().get(0);
            assertEquals(3, t.getNumberOfRows());
            assertEquals("O-1", t.getRow(1).getCell(0).getText());
            assertEquals("镜款O-1", t.getRow(1).getCell(1).getText());
            // 数据中没有的明细列：渲染为空，不报错
            assertEquals("", t.getRow(1).getCell(2).getText());
            // 隔行底色依旧按数据行生效
            assertEquals("F2F2F2", fill(t, 2, 0));
        }
    }

    @Test
    void file_template_change_is_hot_loaded_by_mtime() throws Exception {
        File tpl = writeTemplate(new File("target/test-templates/hot.docx"), " 版本=V1");
        WeeklyReportRenderer renderer = newRenderer(tpl);
        renderer.init();

        byte[] v1 = renderer.render(sampleData());
        assertTrue(headText(v1).contains("版本=V1"));

        // 保证 mtime 变化（部分文件系统秒级精度）
        Thread.sleep(1100);
        writeTemplate(tpl, " 版本=V2");

        byte[] v2 = renderer.render(sampleData());
        assertTrue(headText(v2).contains("版本=V2"), "外部模板改动应被 mtime 热加载");
        assertFalse(headText(v2).contains("V1"));
    }

    private static String fill(XWPFTable t, int row, int col) {
        XWPFTableCell cell = t.getRow(row).getCell(col);
        if (cell.getCTTc().getTcPr() == null || !cell.getCTTc().getTcPr().isSetShd()) {
            return null;
        }
        return cell.getCTTc().getTcPr().getShd().xgetFill().getStringValue();
    }

    private static String headText(byte[] docx) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx))) {
            return doc.getParagraphs().get(0).getText();
        }
    }
}
