package com.evops.report.policy;

import com.deepoove.poi.plugin.table.LoopRowTableRenderPolicy;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd;

/**
 * 明细表循环渲染：沿用 poi-tl {@link LoopRowTableRenderPolicy} 的单行式行为
 * （构造 onSameLine=true）——模板里表头下一行的首个单元格写
 * {@code {{orders}}[orderNo]}（标签与首列标签同格、不同 run），该行直接作为数据行模板，
 * 按明细集合复制行并替换行内 [列名]，循环结束后原模板行移除，不留空行；
 * 随后在 afterloop 里给数据行刷隔行底色。
 * <p>
 * 隔行底色按"数据行相对序号奇偶"统一刷底纹（整行所有单元格），不依赖模板行原本底纹——
 * 模板里不用手工给行上色，换隔行颜色只改配置。表头行（{@code headerRows} 行）不上色。
 */
public class ZebraLoopRowTablePolicy extends LoopRowTableRenderPolicy {

    private final String fillHex;
    private final int headerRows;

    public ZebraLoopRowTablePolicy(String fillHex, int headerRows) {
        // 单行式（标签即数据模板行），循环行内列标签用方括号：[orderNo]、[lensName] …
        super(true);
        this.fillHex = fillHex;
        this.headerRows = Math.max(0, headerRows);
    }

    @Override
    protected void afterloop(XWPFTable table, Object data) {
        if (fillHex == null || fillHex.trim().isEmpty()) {
            return;
        }
        // 模板行已被父类移除：前 headerRows 行是表头（不上色），其后全部是本次循环的数据行，
        // 按数据行 0 起相对序号隔行上色
        int dataIndex = 0;
        for (int rowIdx = headerRows; rowIdx < table.getNumberOfRows(); rowIdx++) {
            if (dataIndex % 2 == 1) { // 第 2、4、6… 条数据行上底色
                XWPFTableRow row = table.getRow(rowIdx);
                for (XWPFTableCell cell : row.getTableCells()) {
                    shade(cell, fillHex);
                }
            }
            dataIndex++;
        }
    }

    /** 单元格底纹：clear 模式填充整格（hex 如 F2F6FB） */
    private void shade(XWPFTableCell cell, String hex) {
        CTTcPr pr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        CTShd shd = pr.isSetShd() ? pr.getShd() : pr.addNewShd();
        shd.setVal(STShd.CLEAR);
        shd.setColor("auto");
        shd.setFill(hex);
    }
}
