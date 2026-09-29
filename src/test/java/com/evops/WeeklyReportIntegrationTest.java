package com.evops;

import com.evops.report.config.ReportProperties;
import com.evops.report.model.WeeklyReportSnapshot;
import com.evops.report.service.WeeklyReportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.annotation.Resource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验光师加工单周报集成测试：
 * 汇总口径（总数/返修率/镜款分布，排除冲销与周期外）、poi-tl docx 渲染（变量替换、
 * 明细循环行展开、隔行底色）、批量按店 ZIP、导出留痕、模板变量改名不改代码。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(WeeklyReportIntegrationTest.TestShiroBypass.class)
class WeeklyReportIntegrationTest {

    @TestConfiguration
    static class TestShiroBypass {
        @Bean
        ShiroFilterChainDefinition shiroFilterChainDefinition() {
            DefaultShiroFilterChainDefinition chain = new DefaultShiroFilterChainDefinition();
            chain.addPathDefinition("/**", "anon");
            return chain;
        }
    }

    @Resource
    private MockMvc mockMvc;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private WeeklyReportService reportService;
    @Resource
    private ReportProperties properties;

    private static final AtomicLong OPERATOR_SEQ = new AtomicLong(5000);
    private static final AtomicLong REQ_SEQ = new AtomicLong(8000);

    private LocalDate today = LocalDate.now();

    // ---------------- 口径 + docx 渲染 + 留痕 ----------------

    @Test
    void weekly_docx_renders_vars_loop_rows_and_zebra() throws Exception {
        long lensA = createLens("L-RPT-A", "1.60非球面");
        long lensB = createLens("L-RPT-B", "1.67非球面");

        // 本周期 S50 四单（3 A + 1 B），其中一张 A 片单随后冲销 → 留存 A×2、B×1
        createOrder("O-RPT-1", lensA, "S50");
        createOrder("O-RPT-2", lensA, "S50");
        long cancelId = createOrder("O-RPT-3", lensA, "S50");
        createOrder("O-RPT-4", lensB, "S50");
        mockMvc.perform(post("/api/orders/" + cancelId + "/cancel")
                        .header("X-Operator-Id", "5100").header("X-Request-No", "RPT-CANCEL"))
                .andExpect(status().isOk());
        // 周期外（昨天）同店单：不计入
        jdbcTemplate.update("INSERT INTO t_process_order (order_no, customer_name, store_code, lens_id,"
                + " order_status, request_no, created_time) VALUES ('O-RPT-OLD',?,'S50',"
                + lensA + ",'CREATED','RPT-OLD',?)", "昨日顾客", LocalDateTime.now().minusDays(1));

        // 一张本周期评审通过变更 → 1 张返修；待评审/驳回不计
        approveChange("C-RPT-1", "O-RPT-1", "REWRITE");
        pendingChange("C-RPT-2", "O-RPT-2");
        String rejected = "C-RPT-3";
        submitChange(rejected, "O-RPT-4", "ALTER", "RPT-CHG-3");
        scheduleChange(rejected, "RPT-SCH-3", 0);
        reviewChange(rejected, false, "RPT-REV-3");

        JsonNode preview = getJson(get("/api/reports/weekly/preview")
                .param("storeCode", "S50")
                .param("periodStart", today.toString())
                .param("periodEnd", today.toString()));
        JsonNode data = preview.get("data");
        assertEquals(3, data.get("totalCount").asInt(), "冲销单与周期外单不计入");
        assertEquals(1, data.get("reworkCount").asInt());
        assertEquals("33.33%", data.get("reworkRate").asText());
        assertTrue(data.get("lensSummary").asText().contains("1.60非球面×2"),
                "镜款分布按片计数：" + data.get("lensSummary").asText());
        assertTrue(data.get("lensSummary").asText().contains("1.67非球面×1"));
        // 客户打码
        boolean masked = true;
        for (JsonNode row : data.get("rows")) {
            if (row.get("orderNo").asText().equals("O-RPT-1")) {
                assertEquals("返修", row.get("reworkText").asText());
            }
            masked &= !"王顾客".equals(row.get("customer").asText());
        }
        assertTrue(masked, "客户姓名进报告应打码");

        // 导出 docx
        MockHttpServletResponse resp = mockMvc.perform(get("/api/reports/weekly/docx")
                        .param("storeCode", "S50")
                        .param("periodStart", today.toString())
                        .param("periodEnd", today.toString()))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                resp.getContentType().split(";")[0]);
        String disposition = resp.getHeader("Content-Disposition");
        assertTrue(disposition != null && disposition.contains("filename*=UTF-8''"));

        ParsedDocx doc = parse(resp.getContentAsByteArray());
        // 表头 1 行 + 数据 3 行（模板行移除，不留空行）
        XWPFTable table = doc.table;
        assertEquals(4, table.getNumberOfRows(), "循环展开后应为表头+3 数据行");
        assertEquals("加工单号", table.getRow(0).getCell(0).getText().trim());
        List<String> orderNos = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            orderNos.add(table.getRow(i).getCell(0).getText().trim());
        }
        assertTrue(orderNos.containsAll(Arrays.asList("O-RPT-1", "O-RPT-2", "O-RPT-4")),
                "明细应为 3 张存活单（冲销单不入表）：" + orderNos);
        assertFalse(orderNos.contains("O-RPT-3"));
        // 全部模板标签已被替换
        assertFalse(doc.allText.contains("{{"), "存在未替换变量：" + doc.allText);
        assertFalse(doc.allText.contains("[orderNo]"));
        assertTrue(doc.allText.contains("33.33%"));
        assertTrue(doc.allText.contains("1.60非球面×2"));

        // 隔行底色：数据行第 2 行（表第 3 行）有底色，第 1 行无
        String row1Fill = fillOf(table.getRow(1));
        String row2Fill = fillOf(table.getRow(2));
        String row3Fill = fillOf(table.getRow(3));
        assertFalse("F2F6FB".equalsIgnoreCase(row1Fill), "首条数据行不上底色");
        assertEquals("F2F6FB", row2Fill == null ? null : row2Fill.toUpperCase(), "第二条数据行上隔行底色");
        assertFalse("F2F6FB".equalsIgnoreCase(row3Fill), "第三条数据行不上底色");

        // 留痕一行
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_weekly_report_log WHERE store_code='S50' AND batch_no IS NULL"
                + " AND total_count=3 AND rework_count=1 AND rework_rate='33.33%'", Integer.class);
        assertEquals(1, logs);
    }

    // ---------------- 批量按店 ZIP ----------------

    @Test
    void weekly_batch_outputs_one_docx_per_store_zip_and_logs() throws Exception {
        long lensId = createLens("L-RPT-BX", "1.56普通片");
        createOrder("O-BX-S60-1", lensId, "S60");
        createOrder("O-BX-S61-1", lensId, "S61");

        String body = "{\"storeCodes\":[\"S60\",\"S61\",\"S62\"],\"periodStart\":\"" + today
                + "\",\"periodEnd\":\"" + today + "\"}";
        MockHttpServletResponse resp = mockMvc.perform(post("/api/reports/weekly/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "5200").header("X-Request-No", "RPT-BATCH-1")
                        .content(body))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertEquals("application/zip", resp.getContentType().split(";")[0]);
        String batchNo = resp.getHeader("X-Batch-No");
        assertTrue(batchNo != null && batchNo.startsWith("RPT-"));
        assertEquals("3", resp.getHeader("X-File-Count"), "即使 S62 无单也出空表文件");

        List<String> entries = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(
                new ByteArrayInputStream(resp.getContentAsByteArray()), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.add(entry.getName());
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = zis.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                // 每个条目都能被 POI 正常打开
                try (XWPFDocument ignored = new XWPFDocument(new ByteArrayInputStream(bos.toByteArray()))) {
                    // 仅校验是合法 docx
                }
            }
        }
        assertEquals(3, entries.size());
        assertTrue(entries.stream().anyMatch(n -> n.contains("S60") && n.endsWith(".docx")));
        assertTrue(entries.stream().anyMatch(n -> n.contains("S61") && n.endsWith(".docx")));
        assertTrue(entries.stream().anyMatch(n -> n.contains("S62") && n.endsWith(".docx")));

        // 三店留痕共享批次号
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_weekly_report_log WHERE batch_no=?", Integer.class, batchNo);
        assertEquals(3, logs);
    }

    @Test
    void weekly_batch_without_store_lists_uses_all_stores_in_period() throws Exception {
        long lensId = createLens("L-RPT-ALL", "1.56防蓝光");
        createOrder("O-ALL-S66-1", lensId, "S66");
        createOrder("O-ALL-S67-1", lensId, "S67");
        String body = "{\"periodStart\":\"" + today + "\",\"periodEnd\":\"" + today + "\"}";
        JsonNode resp = getJson(post("/api/reports/weekly/batch/preview")
                .contentType(MediaType.APPLICATION_JSON).content(body));
        List<String> stores = new ArrayList<>();
        resp.get("data").forEach(s -> stores.add(s.get("storeCode").asText()));
        assertTrue(stores.containsAll(Arrays.asList("S66", "S67")),
                "不传门店时应覆盖周期内全部有单门店：" + stores);
    }

    @Test
    void weekly_rejects_bad_range_and_missing_store() throws Exception {
        mockMvc.perform(get("/api/reports/weekly/preview")
                        .param("storeCode", "S60")
                        .param("periodStart", today.toString())
                        .param("periodEnd", today.minusDays(1).toString()))
                .andExpect(jsonPath("$.code").value("REPORT_DATE_RANGE_INVALID"));
        mockMvc.perform(get("/api/reports/weekly/docx")
                        .param("periodStart", today.toString())
                        .param("periodEnd", today.toString()))
                .andExpect(jsonPath("$.code").value("REPORT_STORE_REQUIRED"));
    }

    // ---------------- 模板变量改名不改代码 ----------------

    @Test
    void renamed_template_tags_work_via_config_without_code_change() throws Exception {
        // 自包含数据：独立门店 S68
        long lensId = createLens("L-RPT-RN", "1.60变色片");
        createOrder("O-RN-S68-1", lensId, "S68");

        // 复制随包模板，把门店变量与明细标签改名，模拟验光师调整模板
        byte[] templateBytes;
        try (java.io.InputStream in = getClass().getResourceAsStream(
                "/report/templates/weekly-order-summary.docx")) {
            templateBytes = readAll(in);
        }
        Path tmpDir = Files.createTempDirectory("rpt-tpl");
        Path tpl = tmpDir.resolve("renamed.docx");
        // docx 是 zip，直接改 word/document.xml 内的标签文本
        rewriteDocxXml(templateBytes, tpl, xml -> xml
                .replace("{{storeCode}}", "{{menDian}}")
                .replace("{{orders}}", "{{mingXi}}")
                .replace("[orderNo]", "[danHao]"));

        String oldPath = properties.getTemplatePath();
        String oldStoreTag = properties.getTags().getStoreCode();
        String oldOrdersTag = properties.getTags().getOrders();
        String oldOrderNoCol = properties.getTags().getColumns().getOrderNo();
        try {
            properties.setTemplatePath(tpl.toUri().toString());
            properties.getTags().setStoreCode("menDian");
            properties.getTags().setOrders("mingXi");
            properties.getTags().getColumns().setOrderNo("danHao");

            WeeklyReportSnapshot snapshot = reportService.preview("S68", today, today);
            byte[] docx = renderRaw(snapshot);
            ParsedDocx doc = parse(docx);
            assertTrue(doc.allText.contains("S68"), "改名后的门店变量应被替换为门店号");
            assertFalse(doc.allText.contains("{{menDian}}"), "改名变量未替换");
            assertFalse(doc.allText.contains("{{mingXi}}"));
            // 循环行首列用 [danHao] 展开，加工单号出现
            assertTrue(doc.allText.contains("O-RN-S68-1"));
            assertFalse(doc.allText.contains("[danHao]"));
        } finally {
            properties.setTemplatePath(oldPath);
            properties.getTags().setStoreCode(oldStoreTag);
            properties.getTags().setOrders(oldOrdersTag);
            properties.getTags().getColumns().setOrderNo(oldOrderNoCol);
        }
    }

    // ---------------- 辅助 ----------------

    private byte[] renderRaw(WeeklyReportSnapshot snapshot) {
        // 复用 service 的内部渲染：直接走单店导出但不要其留痕副作用，故用 renderer 反射不便，
        // 这里改为通过 preview 已有数据 + 调用 storeExporter 会留痕；留痕不影响断言。
        return reportService.generateOne(snapshot.getStoreCode(),
                snapshot.getPeriodStart(), snapshot.getPeriodEnd()).getContent();
    }

    private void rewriteDocxXml(byte[] docx, Path target, java.util.function.UnaryOperator<String> transform)
            throws Exception {
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new ByteArrayInputStream(docx), StandardCharsets.UTF_8);
             java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                     Files.newOutputStream(target), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                ZipEntry out = new ZipEntry(entry.getName());
                zos.putNextEntry(out);
                byte[] bytes = readAll(zis);
                if ("word/document.xml".equals(entry.getName())) {
                    String xml = new String(bytes, StandardCharsets.UTF_8);
                    bytes = transform.apply(xml).getBytes(StandardCharsets.UTF_8);
                }
                zos.write(bytes);
                zos.closeEntry();
            }
        }
    }

    private byte[] readAll(java.io.InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private ParsedDocx parse(byte[] bytes) throws Exception {
        ParsedDocx parsed = new ParsedDocx();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            parsed.table = doc.getTables().get(0);
            StringBuilder sb = new StringBuilder();
            for (org.apache.poi.xwpf.usermodel.XWPFParagraph p : doc.getParagraphs()) {
                sb.append(p.getText());
            }
            for (XWPFTableRow row : parsed.table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    sb.append(cell.getText());
                }
            }
            parsed.allText = sb.toString();
        }
        return parsed;
    }

    private String fillOf(XWPFTableRow row) {
        XWPFTableCell cell = row.getCell(0);
        if (cell.getCTTc().isSetTcPr() && cell.getCTTc().getTcPr().isSetShd()) {
            // getFill() 返回 STHexColor，取其字符串值（如 f2f6fb）
            return cell.getCTTc().getTcPr().getShd().xgetFill().getStringValue();
        }
        return null;
    }

    private JsonNode getJson(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        String resp = mockMvc.perform(req).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp);
    }

    private long createLens(String code, String name) throws Exception {
        String body = "{\"lensCode\":\"" + code + "\",\"lensName\":\"" + name + "\","
                + "\"lensType\":\"单光\",\"price\":399.00}";
        String resp = mockMvc.perform(post("/api/lens").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", String.valueOf(OPERATOR_SEQ.incrementAndGet()))
                        .header("X-Request-No", "L-" + code)
                        .content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("data").get("id").asLong();
    }

    private long createOrder(String orderNo, long lensId, String storeCode) throws Exception {
        String body = "{\"orderNo\":\"" + orderNo + "\",\"lensId\":" + lensId
                + ",\"customerName\":\"王顾客\",\"storeCode\":\"" + storeCode + "\","
                + "\"prescriptions\":["
                + "{\"eye\":\"L\",\"sphere\":-1.00},"
                + "{\"eye\":\"R\",\"sphere\":-1.00}]}";
        String resp = mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", String.valueOf(OPERATOR_SEQ.incrementAndGet()))
                        .header("X-Request-No", "REQ-" + REQ_SEQ.incrementAndGet())
                        .content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("data").get("id").asLong();
    }

    private void submitChange(String changeNo, String orderNo, String type, String reqNo) throws Exception {
        mockMvc.perform(post("/api/order-changes").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", String.valueOf(OPERATOR_SEQ.incrementAndGet()))
                        .header("X-Request-No", reqNo)
                        .content("{\"changeNo\":\"" + changeNo + "\",\"orderNo\":\"" + orderNo + "\","
                                + "\"changeType\":\"" + type + "\",\"reason\":\"报告测试\"}"))
                .andExpect(jsonPath("$.code").value("OK"));
    }

    private void scheduleChange(String changeNo, String reqNo, int plusDays) throws Exception {
        String t = LocalDateTime.now().plusDays(plusDays).plusHours(1).withNano(0).toString();
        mockMvc.perform(post("/api/order-changes/" + changeNo + "/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", String.valueOf(OPERATOR_SEQ.incrementAndGet()))
                        .header("X-Request-No", reqNo)
                        .content("{\"reviewTime\":\"" + t + "\"}"))
                .andExpect(jsonPath("$.code").value("OK"));
    }

    private void reviewChange(String changeNo, boolean approved, String reqNo) throws Exception {
        mockMvc.perform(post("/api/order-changes/" + changeNo + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", String.valueOf(OPERATOR_SEQ.incrementAndGet()))
                        .header("X-Request-No", reqNo)
                        .content("{\"approved\":" + approved + "}"))
                .andExpect(jsonPath("$.code").value("OK"));
    }

    private void approveChange(String changeNo, String orderNo, String type) throws Exception {
        submitChange(changeNo, orderNo, type, "RPT-CHG-" + changeNo);
        scheduleChange(changeNo, "RPT-SCH-" + changeNo, 0);
        reviewChange(changeNo, true, "RPT-REV-" + changeNo);
    }

    private void pendingChange(String changeNo, String orderNo) throws Exception {
        submitChange(changeNo, orderNo, "REWRITE", "RPT-CHG-" + changeNo);
    }

    private static class ParsedDocx {
        XWPFTable table;
        String allText;
    }
}
