package com.evops.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.annotation.Resource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 返修登记 + 加工单周汇总报告（poi-tl）端到端集成测试：
 * 返修仅取镜可登记、状态不变、重复提交兜底；
 * 单店出 docx（变量/明细/隔行底色/表头底）、指定多店打包 zip、无数据与周期校验、导出留痕。
 * 各用例用独立门店/单号前缀（同一测试类共享内存 H2，方法间不回滚）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(WeeklyReportIntegrationTest.TestShiroBypass.class)
class WeeklyReportIntegrationTest {

    private static final String DOCX_CT =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

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
    private org.springframework.test.web.servlet.MockMvc mockMvc;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private JdbcTemplate jdbcTemplate;

    private long opSeq = 2000;

    /** 一份测试数据：三家门店的编码（各用例独立前缀，互不污染） */
    private static class Seed {
        String storeA, storeB, storeC;
    }

    // ---------------- 返修登记规则 ----------------

    @Test
    void rework_only_after_pickup_and_keeps_status_duplicate_blocked() throws Exception {
        String tag = "RW1";
        long lensId = createLens("L-" + tag, "镜款RW", "REQ-" + tag + "-L");
        long orderId = createOrder("O-" + tag, lensId, "S" + tag, "REQ-" + tag + "-O",
                "-1.00", "-1.00", null);

        // 未取镜不可登记返修
        mockMvc.perform(post("/api/orders/" + orderId + "/rework")
                        .header("X-Operator-Id", String.valueOf(++opSeq))
                        .header("X-Request-No", "REQ-" + tag + "-N1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"度数不符\"}"))
                .andExpect(jsonPath("$.code").value("ORDER_NOT_PICKED_UP"));

        // 走到已取镜
        act(orderId, "start", "REQ-" + tag + "-S", null);
        act(orderId, "finish", "REQ-" + tag + "-F", null);
        act(orderId, "pickup", "REQ-" + tag + "-P", "2026-09-22T10:00:00");

        // 登记返修成功，加工单状态保持 PICKED_UP
        mockMvc.perform(post("/api/orders/" + orderId + "/rework")
                        .header("X-Operator-Id", String.valueOf(++opSeq))
                        .header("X-Request-No", "REQ-" + tag + "-RW1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"镜腿松\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.reworkNo").isNotEmpty());
        assertEquals("PICKED_UP", orderStatus(orderId));

        // 同一单可再次返修（一单多次），不同请求号
        mockMvc.perform(post("/api/orders/" + orderId + "/rework")
                        .header("X-Operator-Id", String.valueOf(++opSeq))
                        .header("X-Request-No", "REQ-" + tag + "-RW2")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"划痕\"}"))
                .andExpect(jsonPath("$.code").value("OK"));

        // 重复提交（同请求号）库层兜底
        mockMvc.perform(post("/api/orders/" + orderId + "/rework")
                        .header("X-Operator-Id", String.valueOf(++opSeq))
                        .header("X-Request-No", "REQ-" + tag + "-RW2"))
                .andExpect(jsonPath("$.code").value("REQUEST_DUPLICATED"));

        // 返修表两行；详情含 reworks
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_process_rework WHERE order_id=?", Integer.class, orderId);
        assertEquals(2, cnt);
        mockMvc.perform(get("/api/orders/" + orderId))
                .andExpect(jsonPath("$.data.reworks.length()").value(2));
    }

    // ---------------- 单店 docx 导出 ----------------

    @Test
    void export_single_store_docx_with_variables_and_zebra() throws Exception {
        Seed s = seedReportData("A");

        byte[] body = mockMvc.perform(get("/api/reports/weekly-orders")
                        .param("start", "2026-09-21").param("end", "2026-09-27")
                        .param("storeCode", s.storeA)
                        .header("X-Operator-Id", String.valueOf(++opSeq))
                        .header("X-Request-No", "RPT-A-1"))
                .andExpect(status().isOk())
                .andExpect(r -> assertTrue(
                        r.getResponse().getContentType().startsWith(DOCX_CT)))
                .andExpect(r -> assertTrue(
                        r.getResponse().getHeader("Content-Disposition").contains("filename*=UTF-8''")))
                .andExpect(r -> assertTrue(
                        r.getResponse().getHeader("Content-Disposition").contains(".docx")))
                .andReturn().getResponse().getContentAsByteArray();

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(body))) {
            // 抬头变量被替换
            String allText = paragraphsText(doc);
            assertTrue(allText.contains(s.storeA), "抬头应含门店编码");
            assertTrue(allText.contains("2026-09-21"), "应含周期起");
            assertTrue(allText.contains("50.00%"), "两单中单一单返修，返修率应为 50.00%");
            assertFalse(allText.contains("{{"), "不应残留未渲染变量");

            XWPFTable t = doc.getTables().get(0);
            // 表头 + 两张取镜单
            assertEquals(3, t.getNumberOfRows());
            // 加工单号、镜款自动填充
            assertEquals("O-A-A1", cell(t, 1, 0));
            assertEquals("镜款A", cell(t, 1, 2));
            assertEquals("O-A-A2", cell(t, 2, 0));
            // 返修次数 / 是否返修
            assertEquals("1", cell(t, 1, 6));
            assertEquals("是", cell(t, 1, 7));
            assertEquals("0", cell(t, 2, 6));
            assertEquals("否", cell(t, 2, 7));

            // 表头深蓝底；第一条数据行无隔行底，第二条有浅灰底
            assertEquals("4472C4", fill(t, 0, 0));
            assertNoZebra(t, 1);
            assertZebra(t, 2, "F2F2F2");
        }

        // 导出留痕一行，门店范围精确为本店
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_report_export_log WHERE report_type='WEEKLY_ORDER' AND store_codes=?",
                Integer.class, s.storeA);
        assertEquals(1, logs);
    }

    // ---------------- 指定多店批量分别出文件（zip） ----------------

    @Test
    void export_multiple_stores_returns_zip_one_docx_per_store() throws Exception {
        Seed s = seedReportData("B");

        byte[] body = mockMvc.perform(get("/api/reports/weekly-orders")
                        .param("start", "2026-09-21").param("end", "2026-09-27")
                        .param("storeCode", s.storeA).param("storeCode", s.storeB)
                        .header("X-Operator-Id", String.valueOf(++opSeq))
                        .header("X-Request-No", "RPT-B-1"))
                .andExpect(status().isOk())
                .andExpect(r -> assertTrue(
                        r.getResponse().getContentType().startsWith("application/zip")))
                .andExpect(r -> assertTrue(
                        r.getResponse().getHeader("Content-Disposition").contains(".zip")))
                .andReturn().getResponse().getContentAsByteArray();

        int entries = 0;
        boolean hasA = false, hasB = false;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(body))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries++;
                byte[] docxBytes = readEntry(zis);
                // 每个条目都应是合法 docx（在独立流上打开，避免关闭外层 zip）
                try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
                    assertNotNull(doc.getTables());
                    assertEquals(1, doc.getTables().size());
                }
                if (entry.getName().contains(s.storeA)) hasA = true;
                if (entry.getName().contains(s.storeB)) hasB = true;
            }
        }
        assertEquals(2, entries, "两家门店各一个 docx");
        assertTrue(hasA && hasB, "zip 内文件名应区分门店");
    }

    // ---------------- 无数据 / 周期校验 / 门店收窄 ----------------

    @Test
    void empty_period_and_bad_period_rejected() throws Exception {
        // 周期内无取镜单
        mockMvc.perform(get("/api/reports/weekly-orders")
                        .param("start", "2026-01-01").param("end", "2026-01-07"))
                .andExpect(jsonPath("$.code").value("REPORT_NO_DATA"));

        // 止日早于起日
        mockMvc.perform(get("/api/reports/weekly-orders")
                        .param("start", "2026-09-27").param("end", "2026-09-21"))
                .andExpect(jsonPath("$.code").value("REPORT_PERIOD_INVALID"));

        // 指定一个全库都不存在的门店：NO_DATA，而不是混入他店
        mockMvc.perform(get("/api/reports/weekly-orders")
                        .param("start", "2026-09-21").param("end", "2026-09-27")
                        .param("storeCode", "S-ZX-99"))
                .andExpect(jsonPath("$.code").value("REPORT_NO_DATA"));
    }

    @Test
    void orders_outside_period_or_not_picked_excluded() throws Exception {
        Seed s = seedReportData("C");
        byte[] body = mockMvc.perform(get("/api/reports/weekly-orders")
                        .param("start", "2026-09-21").param("end", "2026-09-27")
                        .param("storeCode", s.storeC)
                        .header("X-Request-No", "RPT-C-1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(body))) {
            XWPFTable t = doc.getTables().get(0);
            // 仅 09-24 取镜的 O-C-C1 入册；09-10 取镜的、仍在加工的都排除
            assertEquals(2, t.getNumberOfRows());
            assertEquals("O-C-C1", cell(t, 1, 0));
        }
    }

    // ---------------- 数据装配 ----------------

    private Seed seedReportData(String tag) throws Exception {
        Seed seed = new Seed();
        seed.storeA = "S" + tag + "1";
        seed.storeB = "S" + tag + "2";
        seed.storeC = "S" + tag + "3";

        long lensA = createLens("L-" + tag + "-A", "镜款A", "REQ-" + tag + "-LA");
        long lensB = createLens("L-" + tag + "-B", "镜款B", "REQ-" + tag + "-LB");
        long lensC = createLens("L-" + tag + "-C", "镜款C", "REQ-" + tag + "-LC");

        // storeA：两单，A1 返修 1 次（返修率 50%）
        long a1 = createOrder("O-" + tag + "-A1", lensA, seed.storeA, "REQ-" + tag + "-A1",
                "-1.00", "-1.00", null);
        flowToPickup(a1, tag + "A1", "2026-09-22T09:00:00");
        registerRework(a1, "REQ-" + tag + "-A1RW", "返修一次");
        long a2 = createOrder("O-" + tag + "-A2", lensA, seed.storeA, "REQ-" + tag + "-A2",
                "-1.25", "-1.25", null);
        flowToPickup(a2, tag + "A2", "2026-09-23T09:00:00");

        // storeB：一单，返修 2 次
        long b1 = createOrder("O-" + tag + "-B1", lensB, seed.storeB, "REQ-" + tag + "-B1",
                "-2.00", "-2.00", null);
        flowToPickup(b1, tag + "B1", "2026-09-24T09:00:00");
        registerRework(b1, "REQ-" + tag + "-B1RW1", "返修1");
        registerRework(b1, "REQ-" + tag + "-B1RW2", "返修2");

        // storeC：周期内一单、周期外一单、未取镜一单
        long c1 = createOrder("O-" + tag + "-C1", lensC, seed.storeC, "REQ-" + tag + "-C1",
                "-1.00", "-1.00", null);
        flowToPickup(c1, tag + "C1", "2026-09-24T15:00:00");
        long c2 = createOrder("O-" + tag + "-C2", lensC, seed.storeC, "REQ-" + tag + "-C2",
                "-1.00", "-1.00", null);
        flowToPickup(c2, tag + "C2", "2026-09-10T15:00:00");
        long c3 = createOrder("O-" + tag + "-C3", lensC, seed.storeC, "REQ-" + tag + "-C3",
                "-1.00", "-1.00", null);
        act(c3, "start", "REQ-" + tag + "-C3S", null); // 仍加工中，排除

        return seed;
    }

    // ---------------- 辅助 ----------------

    private long createLens(String code, String name, String reqNo) throws Exception {
        String body = "{\"lensCode\":\"" + code + "\",\"lensName\":\"" + name + "\","
                + "\"lensType\":\"单光\",\"price\":399.00}";
        JsonNode node = objectMapper.readTree(mockMvc.perform(post("/api/lens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", String.valueOf(++opSeq))
                        .header("X-Request-No", reqNo).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return node.get("data").get("id").asLong();
    }

    private long createOrder(String orderNo, long lensId, String store, String reqNo,
                             String lSphere, String rSphere, String bizTime) throws Exception {
        String body = "{\"orderNo\":\"" + orderNo + "\",\"lensId\":" + lensId
                + ",\"customerName\":\"王五\",\"storeCode\":\"" + store + "\","
                + "\"prescriptions\":["
                + "{\"eye\":\"L\",\"sphere\":" + lSphere + "},"
                + "{\"eye\":\"R\",\"sphere\":" + rSphere + "}]}";
        MockHttpServletRequestBuilder req = post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .header("X-Operator-Id", String.valueOf(++opSeq))
                .header("X-Request-No", reqNo).content(body);
        if (bizTime != null) req.header("X-Biz-Time", bizTime);
        JsonNode node = objectMapper.readTree(
                mockMvc.perform(req).andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value("OK"))
                        .andReturn().getResponse().getContentAsString());
        return node.get("data").get("id").asLong();
    }

    private void act(long orderId, String action, String reqNo, String bizTime) throws Exception {
        MockHttpServletRequestBuilder req = post("/api/orders/" + orderId + "/" + action)
                .header("X-Operator-Id", String.valueOf(++opSeq))
                .header("X-Request-No", reqNo);
        if (bizTime != null) req.header("X-Biz-Time", bizTime);
        mockMvc.perform(req).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"));
    }

    private void flowToPickup(long orderId, String prefix, String pickupBizTime) throws Exception {
        act(orderId, "start", "REQ-" + prefix + "-S", null);
        act(orderId, "finish", "REQ-" + prefix + "-F", null);
        act(orderId, "pickup", "REQ-" + prefix + "-P", pickupBizTime);
    }

    private void registerRework(long orderId, String reqNo, String reason) throws Exception {
        mockMvc.perform(post("/api/orders/" + orderId + "/rework")
                        .header("X-Operator-Id", String.valueOf(++opSeq))
                        .header("X-Request-No", reqNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"));
    }

    private String orderStatus(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT order_status FROM t_process_order WHERE id=?", String.class, id);
    }

    private static String cell(XWPFTable t, int row, int col) {
        return t.getRow(row).getCell(col).getText();
    }

    private static String fill(XWPFTable t, int row, int col) {
        XWPFTableCell cell = t.getRow(row).getCell(col);
        if (cell.getCTTc().getTcPr() == null || !cell.getCTTc().getTcPr().isSetShd()) {
            return null;
        }
        return cell.getCTTc().getTcPr().getShd().xgetFill().getStringValue();
    }

    private static void assertZebra(XWPFTable t, int row, String color) {
        for (int c = 0; c < 8; c++) {
            assertEquals(color, fill(t, row, c), "第 " + row + " 行第 " + c + " 列应有隔行底色");
        }
    }

    private static void assertNoZebra(XWPFTable t, int row) {
        for (int c = 0; c < 8; c++) {
            assertFalse("F2F2F2".equals(fill(t, row, c)), "奇数数据行不应着隔行底色");
        }
    }

    private static String paragraphsText(XWPFDocument doc) {
        StringBuilder sb = new StringBuilder();
        doc.getParagraphs().forEach(p -> sb.append(p.getText()).append('\n'));
        return sb.toString();
    }

    private static byte[] readEntry(ZipInputStream zis) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = zis.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
