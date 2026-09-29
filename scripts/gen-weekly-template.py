#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成验光师加工单周报 Word 模板（poi-tl）：
    src/main/resources/report/templates/weekly-order-summary.docx

可重复运行（幂等覆盖）。为什么不用 Word 另存：poi-tl 的循环行策略要求
明细表"数据模板行"首格里 {{orders}} 与 [orderNo] 分属不同文本 run（W:R），
Word 手工排版常把相邻文本并到一个 run，导致循环标签识别失败。本脚本直接
写 OOXML，精确控制 run 边界。

模板变量改名后：在 application.yml 的 evops.report.tags 对齐即可，无需改 Java 代码。
"""
import os
import zipfile

OUT = os.path.join(os.path.dirname(__file__), "..",
                   "src", "main", "resources", "report", "templates",
                   "weekly-order-summary.docx")

HEADER_FILL = "D9E2F3"  # 表头底色（隔行数据行底色由 Java 侧配置 F2F6FB 刷）

CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
</Types>
"""

RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>
"""

DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>
"""

STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:docDefaults>
    <w:rPrDefault>
      <w:rPr>
        <w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="宋体"/>
        <w:sz w:val="21"/>
      </w:rPr>
    </w:rPrDefault>
  </w:docDefaults>
  <w:style w:type="paragraph" w:default="1" w:styleId="Normal">
    <w:name w:val="Normal"/>
  </w:style>
</w:styles>
"""

W = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'


def esc(text):
    return (text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))


def run(text, bold=False, size=21):
    """一个文本 run。poi-tl 标签必须整体落在同一个 run 内。"""
    b = "<w:b/>" if bold else ""
    return ('<w:r><w:rPr>%s<w:sz w:val="%d"/></w:rPr>'
            '<w:t xml:space="preserve">%s</w:t></w:r>'
            % (b, size, esc(text)))


def para(runs_xml, align=None, before=0, after=120):
    jc = '<w:jc w:val="%s"/>' % align if align else ""
    return ('<w:p><w:pPr><w:spacing w:before="%d" w:after="%d"/>%s</w:pPr>%s</w:p>'
            % (before, after, jc, runs_xml))


def cell(runs_xml, width, header=False):
    fill = '<w:shd w:val="clear" w:color="auto" w:fill="%s"/>' % HEADER_FILL if header else ""
    bold = '<w:b/>' if header else ""
    # 单元格内段落自己包一层（传入的是 run 列表 XML 或纯 run）
    return ('<w:tc><w:tcPr><w:tcW w:w="%d" w:type="dxa"/>%s'
            '<w:vAlign w:val="center"/></w:tcPr>'
            '<w:p><w:pPr><w:spacing w:before="40" w:after="40"/></w:pPr>'
            '<w:r><w:rPr>%s</w:rPr></w:r>%s</w:p></w:tc>'
            % (width, fill, bold, runs_xml))


HEADERS = ["加工单号", "镜款", "开单日期", "状态", "客户", "返修"]
WIDTHS = [1700, 2200, 1400, 1100, 1200, 900]
# 数据行各列循环标签（方括号），与 application.yml evops.report.tags.columns 对应
COLUMN_TAGS = ["[orderNo]", "[lensName]", "[orderDate]",
               "[statusText]", "[customer]", "[reworkText]"]


def header_row():
    cells = "".join(cell(run(h, bold=True), w, header=True) for h, w in zip(HEADERS, WIDTHS))
    return "<w:tr>%s</w:tr>" % cells


def data_row():
    # 首格：循环标签 {{orders}} 与首列 [orderNo] 必须在不同 run
    first = run("{{orders}}") + run("[orderNo]")
    cells = cell(first, WIDTHS[0])
    for tag, w in zip(COLUMN_TAGS[1:], WIDTHS[1:]):
        cells += cell(run(tag), w)
    return "<w:tr>%s</w:tr>" % cells


def table():
    borders = (
        '<w:tblBorders>'
        '<w:top w:val="single" w:sz="4" w:space="0" w:color="9BBB59"/>'
        '<w:left w:val="single" w:sz="4" w:space="0" w:color="9BBB59"/>'
        '<w:bottom w:val="single" w:sz="4" w:space="0" w:color="9BBB59"/>'
        '<w:right w:val="single" w:sz="4" w:space="0" w:color="9BBB59"/>'
        '<w:insideH w:val="single" w:sz="4" w:space="0" w:color="9BBB59"/>'
        '<w:insideV w:val="single" w:sz="4" w:space="0" w:color="9BBB59"/>'
        '</w:tblBorders>')
    layout = '<w:tblLayout w:type="fixed"/>'
    grid = "<w:tblGrid>" + "".join('<w:gridCol w:w="%d"/>' % w for w in WIDTHS) + "</w:tblGrid>"
    return ('<w:tbl><w:tblPr><w:tblW w:w="0" w:type="auto"/>%s%s'
            '<w:tblLook w:val="04A0"/></w:tblPr>%s%s%s</w:tbl>'
            % (borders, layout, grid, header_row(), data_row()))


def build_document():
    title = para(run("加工单汇总周报", bold=True, size=32), align="center", after=200)

    line1 = para(
        run("门店：", bold=True) + run("{{storeCode}}")
        + run("    统计周期：", bold=True) + run("{{periodStart}}")
        + run(" 至 ") + run("{{periodEnd}}")
        + run("    生成时间：", bold=True) + run("{{generatedAt}}"),
        after=120)

    summary = para(
        run("加工单总数：", bold=True) + run("{{totalCount}}")
        + run("    返修单数：", bold=True) + run("{{reworkCount}}")
        + run("    返修率：", bold=True) + run("{{reworkRate}}"),
        after=80)

    lens = para(
        run("镜款分布：", bold=True) + run("{{lensSummary}}"),
        after=160)

    detail_title = para(run("加工单明细", bold=True, size=24), after=80)

    body = title + line1 + summary + lens + detail_title + table()
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<w:document %s><w:body>%s'
            '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>'
            '<w:pgMar w:top="1440" w:right="1080" w:bottom="1440" w:left="1080"/>'
            '</w:sectPr></w:body></w:document>' % (W, body))


def main():
    out_path = os.path.abspath(OUT)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    files = {
        "[Content_Types].xml": CONTENT_TYPES,
        "_rels/.rels": RELS,
        "word/_rels/document.xml.rels": DOC_RELS,
        "word/styles.xml": STYLES,
        "word/document.xml": build_document(),
    }
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as z:
        for name, content in files.items():
            z.writestr(name, content.encode("utf-8"))
    print("模板已生成：%s" % out_path)


if __name__ == "__main__":
    main()
