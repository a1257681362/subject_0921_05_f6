package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 加工单变更单集成测试：
 * 提交（加工单必须存在/同单待评审唯一/重复提交兜底）→ 安排评审 → 评审通过/驳回留痕；
 * 并回归既有"修改加工单"写路径不受影响（变更单提交流程不变，通过后仍走 PUT /api/orders）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(OrderChangeIntegrationTest.TestShiroBypass.class)
class OrderChangeIntegrationTest {

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

    private static final AtomicLong OPERATOR_SEQ = new AtomicLong(4000);
    private static final AtomicLong REQ_SEQ = new AtomicLong(7000);

    @Test
    void change_full_flow_schedule_then_approve_and_order_edit_unchanged() throws Exception {
        long lensId = createLens("L-CHG-A");
        long orderId = createOrder("O-CHG-A", lensId, "S01");

        String changeNo = submitChange("C-CHG-A", "O-CHG-A", "REWRITE", "CHG-A-1", "复检度数偏差需改方");
        // 安排在后天
        String reviewTime = LocalDateTime.now().plusDays(2).withNano(0).toString();
        mockMvc.perform(post("/api/order-changes/" + changeNo + "/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "4100").header("X-Request-No", "CHG-A-S")
                        .content("{\"reviewTime\":\"" + reviewTime + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.reviewTime").value(reviewTime));

        // 评审通过
        mockMvc.perform(post("/api/order-changes/" + changeNo + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "4101").header("X-Request-No", "CHG-A-R")
                        .content("{\"approved\":true,\"reviewComment\":\"同意改方\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.reviewerId").value(4101));

        // 终态不可再评审、不可再安排
        mockMvc.perform(post("/api/order-changes/" + changeNo + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "4102").header("X-Request-No", "CHG-A-R2")
                        .content("{\"approved\":false}"))
                .andExpect(jsonPath("$.code").value("ORDER_CHANGE_STATUS_ILLEGAL"));
        mockMvc.perform(post("/api/order-changes/" + changeNo + "/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "4103").header("X-Request-No", "CHG-A-S2")
                        .content("{\"reviewTime\":\"" + reviewTime + "\"}"))
                .andExpect(jsonPath("$.code").value("ORDER_CHANGE_STATUS_ILLEGAL"));

        // 流程不变：评审通过后验光师仍走既有修改加工单接口落库
        long version = jdbcTemplate.queryForObject(
                "SELECT version_no FROM t_process_order WHERE id=" + orderId, Long.class);
        String updateBody = "{\"id\":" + orderId + ",\"versionNo\":" + version + ","
                + "\"prescriptions\":["
                + "{\"eye\":\"L\",\"sphere\":-1.50},"
                + "{\"eye\":\"R\",\"sphere\":-1.25}]}";
        mockMvc.perform(put("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "4104").header("X-Request-No", "CHG-A-EDIT")
                        .content(updateBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"));

        // 留痕落库
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_order_change WHERE change_no=? AND review_status='APPROVED'"
                + " AND review_comment='同意改方'", Integer.class, changeNo);
        assertEquals(1, rows);
    }

    @Test
    void reject_flow_and_review_before_schedule_rejected() throws Exception {
        long lensId = createLens("L-CHG-B");
        createOrder("O-CHG-B", lensId, "S02");
        String changeNo = submitChange("C-CHG-B", "O-CHG-B", "ALTER", "CHG-B-1", "顾客要求换镜款");

        // 未安排评审时间不能录结论
        mockMvc.perform(post("/api/order-changes/" + changeNo + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "4200").header("X-Request-No", "CHG-B-R0")
                        .content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value("ORDER_CHANGE_NOT_SCHEDULED"));

        schedule(changeNo, "CHG-B-S", 1);
        mockMvc.perform(post("/api/order-changes/" + changeNo + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "4201").header("X-Request-No", "CHG-B-R1")
                        .content("{\"approved\":false,\"reviewComment\":\"不符合换款条件\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("REJECTED"));

        // 驳回后允许重新提交一张新变更单
        submitChange("C-CHG-B2", "O-CHG-B", "REWRITE", "CHG-B-2", "改方重新申请");
    }

    @Test
    void submit_guards_unknown_order_duplicate_pending_and_request() throws Exception {
        long lensId = createLens("L-CHG-C");
        createOrder("O-CHG-C", lensId, "S03");
        createOrder("O-CHG-C2", lensId, "S03");
        createOrder("O-CHG-C3", lensId, "S03");

        // 加工单不存在
        String body = "{\"changeNo\":\"C-CHG-X\",\"orderNo\":\"O-NO-SUCH\",\"reason\":\"r\"}";
        mockMvc.perform(post("/api/order-changes").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "4300").header("X-Request-No", "CHG-X-1")
                        .content(body))
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        // 第一张待评审单
        submitChange("C-CHG-C1", "O-CHG-C", "REWRITE", "CHG-C-1", "改方");
        // 同一加工单重复待评审：业务层拒绝（先于单号唯一）
        submitChangeExpect("C-CHG-C2", "O-CHG-C", "REWRITE", "CHG-C-2",
                "ORDER_CHANGE_ALREADY_PENDING");
        // 变更单号唯一：换到另一张单，单号仍撞 uk_order_change_no
        submitChangeExpect("C-CHG-C1", "O-CHG-C2", "REWRITE", "CHG-C-2B",
                "ORDER_CHANGE_NO_DUPLICATED");
        // 同一请求号重复提交：再换一张单，请求号撞 uk_order_change_request
        submitChangeExpect("C-CHG-C3", "O-CHG-C3", "REWRITE", "CHG-C-1",
                "REQUEST_DUPLICATED");
        // 非法变更类型（校验最前）
        submitChangeExpect("C-CHG-C4", "O-CHG-C2", "WHATEVER", "CHG-C-4",
                "VALIDATION_FAILED");

        // 查询过滤
        mockMvc.perform(get("/api/order-changes").param("orderNo", "O-CHG-C"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/order-changes/by-no/C-CHG-C1"))
                .andExpect(jsonPath("$.data.storeCode").value("S03"));
    }

    // ---------------- 辅助 ----------------

    private String submitChange(String changeNo, String orderNo, String type, String reqNo, String reason)
            throws Exception {
        String resp = mockMvc.perform(post("/api/order-changes").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", String.valueOf(OPERATOR_SEQ.incrementAndGet()))
                        .header("X-Request-No", reqNo)
                        .content(changeBody(changeNo, orderNo, type, reason)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("data").get("changeNo").asText();
    }

    private void submitChangeExpect(String changeNo, String orderNo, String type, String reqNo,
                                    String expectCode) throws Exception {
        mockMvc.perform(post("/api/order-changes").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", String.valueOf(OPERATOR_SEQ.incrementAndGet()))
                        .header("X-Request-No", reqNo)
                        .content(changeBody(changeNo, orderNo, type, "原因")))
                .andExpect(jsonPath("$.code").value(expectCode));
    }

    private String changeBody(String changeNo, String orderNo, String type, String reason) {
        return "{\"changeNo\":\"" + changeNo + "\",\"orderNo\":\"" + orderNo + "\","
                + "\"changeType\":\"" + type + "\",\"reason\":\"" + reason + "\"}";
    }

    private void schedule(String changeNo, String reqNo, int plusDays) throws Exception {
        String reviewTime = LocalDateTime.now().plusDays(plusDays).withNano(0).toString();
        mockMvc.perform(post("/api/order-changes/" + changeNo + "/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", String.valueOf(OPERATOR_SEQ.incrementAndGet()))
                        .header("X-Request-No", reqNo)
                        .content("{\"reviewTime\":\"" + reviewTime + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"));
    }

    private long createLens(String code) throws Exception {
        String body = "{\"lensCode\":\"" + code + "\",\"lensName\":\"镜片" + code + "\","
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
}
