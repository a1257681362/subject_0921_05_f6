import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;

/**
 * 加工单周汇总报告 docx 模板生成器（poi-tl 语法）。
 *
 * 由 scripts/build-weekly-template.sh 编译运行，产出
 * src/main/resources/templates/weekly-orders.docx。模板改版（增删抬头变量、明细列）
 * 只需改本文件后重跑脚本，或直接在 Word 里改 docx；渲染代码无需改动。
 *
 * 语法约定（与 WeeklyReportRenderer 的 LoopRowTableRenderPolicy(true) 对齐）：
 *   普通变量：     {{storeCode}} {{periodStart}} {{reworkRate}} ...
 *   明细表行循环： 数据行首格 {{orders}} 触发整行复制，列内用方括号变量 [orderNo] [lensName] ...
 *
 * 用法：java TemplateBuilder <输出 docx 路径>
 */
public class TemplateBuilder {

    static final String FONT_HEI = "黑体";
    static final String FONT_SONG = "宋体";

    public static void main(String[] args) throws Exception {
        String outPath = args.length > 0 ? args[0]
                : "src/main/resources/templates/weekly-orders.docx";
        File out = new File(outPath);
        if (out.getParentFile() != null) {
            out.getParentFile().mkdirs();
        }

        try (XWPFDocument doc = new XWPFDocument()) {
            buildTitle(doc);
            buildHeader(doc);
            buildSummary(doc);
            buildDetailTable(doc);
            buildFooter(doc);

            try (FileOutputStream fos = new FileOutputStream(out)) {
                doc.write(fos);
            }
        }
        System.out.println("模板已生成：" + out.getAbsolutePath());
    }

    /** 标题 */
    static void buildTitle(XWPFDocument doc) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        run(p, "加工单周汇总报告", FONT_HEI, 18, true);
    }

    /** 抬头：门店 / 统计周期 / 生成时间 */
    static void buildHeader(XWPFDocument doc) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(120);
        run(p, "门店：", FONT_SONG, 11, true);
        run(p, "{{storeCode}}", FONT_SONG, 11, false);
        run(p, "        统计周期：", FONT_SONG, 11, true);
        run(p, "{{periodStart}}", FONT_SONG, 11, false);
        run(p, " 至 ", FONT_SONG, 11, false);
        run(p, "{{periodEnd}}", FONT_SONG, 11, false);
        run(p, "        生成时间：", FONT_SONG, 11, true);
        run(p, "{{generatedAt}}", FONT_SONG, 11, false);
    }

    /** 汇总区：总数 / 返修单数 / 返修次数 / 返修率 */
    static void buildSummary(XWPFDocument doc) {
        XWPFParagraph h = doc.createParagraph();
        h.setSpacingBefore(200);
        run(h, "一、汇总", FONT_HEI, 12, true);

        XWPFParagraph p = doc.createParagraph();
        run(p, "本周期取镜加工单 ", FONT_SONG, 11, false);
        run(p, "{{orderCount}}", FONT_SONG, 11, true);
        run(p, " 张，其中发生返修 ", FONT_SONG, 11, false);
        run(p, "{{reworkOrderCount}}", FONT_SONG, 11, true);
        run(p, " 张，返修登记共 ", FONT_SONG, 11, false);
        run(p, "{{reworkTotalCount}}", FONT_SONG, 11, true);
        run(p, " 次，返修率 ", FONT_SONG, 11, false);
        run(p, "{{reworkRate}}", FONT_SONG, 11, true);
        run(p, "。", FONT_SONG, 11, false);
    }

    /** 明细表：表头（深色底白字）+ 一行数据模板（{{orders}} 行循环） */
    static void buildDetailTable(XWPFDocument doc) {
        XWPFParagraph h = doc.createParagraph();
        h.setSpacingBefore(200);
        run(h, "二、加工单明细", FONT_HEI, 12, true);

        String[] heads = {"加工单号", "门店", "镜款", "镜款编码", "当前状态", "取镜日期", "返修次数", "是否返修"};
        int[] widths = {1500, 850, 1850, 1450, 1050, 1150, 750, 750};

        XWPFTable table = doc.createTable(2, heads.length);
        fixedLayout(table, widths);

        // 表头
        XWPFTableRow headRow = table.getRow(0);
        for (int i = 0; i < heads.length; i++) {
            XWPFTableCell cell = headRow.getCell(i);
            shade(cell, "4472C4");
            centerText(cell, heads[i], FONT_HEI, 10, true, "FFFFFF");
        }

        // 数据模板行：首格 {{orders}} 触发行复制（onSameLine），列内方括号变量
        XWPFTableRow dataRow = table.getRow(1);
        cellText(dataRow.getCell(0), "{{orders}}[orderNo]", FONT_SONG, 10, false);
        cellText(dataRow.getCell(1), "[storeCode]", FONT_SONG, 10, false);
        cellText(dataRow.getCell(2), "[lensName]", FONT_SONG, 10, false);
        cellText(dataRow.getCell(3), "[lensCode]", FONT_SONG, 10, false);
        cellText(dataRow.getCell(4), "[orderStatus]", FONT_SONG, 10, false);
        cellText(dataRow.getCell(5), "[pickupDate]", FONT_SONG, 10, false);
        cellText(dataRow.getCell(6), "[reworkCount]", FONT_SONG, 10, false);
        cellText(dataRow.getCell(7), "[reworkFlag]", FONT_SONG, 10, false);
        for (int i = 0; i < heads.length; i++) {
            dataRow.getCell(i).setWidth(String.valueOf(widths[i]));
        }
    }

    static void buildFooter(XWPFDocument doc) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(160);
        run(p, "注：返修率 = 周期内有返修登记的取镜单数 ÷ 周期内取镜总单数；返修次数为该单截至报告生成时的累计登记次数。明细表隔行底色由导出程序统一渲染。",
                FONT_SONG, 9, false);
    }

    // ---------------- helpers ----------------

    static XWPFRun run(XWPFParagraph p, String text, String font, int size, boolean bold) {
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setFontFamily(font, XWPFRun.FontCharRange.eastAsia);
        r.setFontFamily(font, XWPFRun.FontCharRange.ascii);
        r.setFontSize(size);
        r.setBold(bold);
        return r;
    }

    static void cellText(XWPFTableCell cell, String text, String font, int size, boolean bold) {
        XWPFParagraph p = cell.getParagraphArray(0);
        if (p == null) {
            p = cell.addParagraph();
        }
        run(p, text, font, size, bold);
    }

    static void centerText(XWPFTableCell cell, String text, String font, int size, boolean bold, String colorHex) {
        XWPFParagraph p = cell.getParagraphArray(0);
        if (p == null) {
            p = cell.addParagraph();
        }
        p.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun r = run(p, text, font, size, bold);
        if (colorHex != null) {
            r.setColor(colorHex);
        }
        // 垂直居中
        CTTcPr pr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        CTVerticalJc vAlign = pr.isSetVAlign() ? pr.getVAlign() : pr.addNewVAlign();
        vAlign.setVal(STVerticalJc.CENTER);
    }

    static void shade(XWPFTableCell cell, String fill) {
        CTTcPr pr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        CTShd shd = pr.isSetShd() ? pr.getShd() : pr.addNewShd();
        shd.setFill(fill);
    }

    /** 固定列宽布局 */
    static void fixedLayout(XWPFTable table, int[] widths) {
        CTTblPr pr = table.getCTTbl().getTblPr() == null ? table.getCTTbl().addNewTblPr() : table.getCTTbl().getTblPr();
        CTTblLayoutType layout = pr.isSetTblLayout() ? pr.getTblLayout() : pr.addNewTblLayout();
        layout.setType(STTblLayoutType.FIXED);
        CTTblWidth tblWidth = pr.isSetTblW() ? pr.getTblW() : pr.addNewTblW();
        int total = 0;
        for (int w : widths) {
            total += w;
        }
        tblWidth.setW(BigInteger.valueOf(total));
        tblWidth.setType(STTblWidth.DXA);

        // 表网格列宽
        CTTblGrid grid = table.getCTTbl().getTblGrid() == null
                ? table.getCTTbl().addNewTblGrid() : table.getCTTbl().getTblGrid();
        grid.getGridColList().clear();
        for (int w : widths) {
            CTTblGridCol col = grid.addNewGridCol();
            col.setW(BigInteger.valueOf(w));
        }
        for (XWPFTableRow row : table.getRows()) {
            for (int i = 0; i < widths.length; i++) {
                XWPFTableCell cell = row.getCell(i);
                CTTcPr tcpr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
                CTTblWidth cw = tcpr.isSetTcW() ? tcpr.getTcW() : tcpr.addNewTcW();
                cw.setW(BigInteger.valueOf(widths[i]));
                cw.setType(STTblWidth.DXA);
            }
        }
    }
}
