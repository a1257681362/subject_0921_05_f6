package com.evops.report.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 周报配置（evops.report.*）。
 * <p>
 * 模板里所有变量名、明细表循环标签、明细列标签都可在这里改名——验光师调整模板
 * （如把 {{storeCode}} 改成 {{menDian}}、把 [orderNo] 改成 [jiaGongDanHao]}）后，
 * 只需改配置对齐，Java 代码一行不动。
 * 生产把 template-path 指向 file: 外部模板，替换文件后下次生成即生效（懒检查 mtime）。
 */
@Component
@ConfigurationProperties(prefix = "evops.report")
@Getter
@Setter
public class ReportProperties {

    /** 周报模板路径：classpath: 随包默认；生产改 file:/path/xxx.docx 外置热更 */
    private String templatePath = "classpath:report/templates/weekly-order-summary.docx";

    /** 明细表隔行底色（十六进制 RGB，如 F2F6FB）；为空则不上色 */
    private String zebraFill = "F2F6FB";

    /** 明细表表头行数（数据行之前不参与隔行上色的行数），默认 1 行表头 */
    private int detailHeaderRow = 1;

    /** 单次批量导出门店数上限，防一次拉全量门店撑爆内存 */
    private int batchStoreLimit = 200;

    /** 模板变量/循环标签名（与 docx 模板一一对应，改名只动配置） */
    private Tags tags = new Tags();

    /** 枚举值在报告里的中文展示（口径词可配置） */
    private Labels labels = new Labels();

    @Getter
    @Setter
    public static class Tags {
        /** 明细表循环标签（绑定 LoopRowTableRenderPolicy，所在行即数据模板行） */
        private String orders = "orders";

        // ---- 页眉/汇总区普通变量 ----
        private String storeCode = "storeCode";
        private String periodStart = "periodStart";
        private String periodEnd = "periodEnd";
        private String generatedAt = "generatedAt";
        private String totalCount = "totalCount";
        private String reworkCount = "reworkCount";
        private String reworkRate = "reworkRate";
        private String lensSummary = "lensSummary";

        /** 明细行内列标签（循环行内以 [列名] 书写） */
        private Columns columns = new Columns();
    }

    @Getter
    @Setter
    public static class Columns {
        private String orderNo = "orderNo";
        private String lensName = "lensName";
        private String orderDate = "orderDate";
        private String statusText = "statusText";
        private String customer = "customer";
        private String reworkText = "reworkText";
    }

    @Getter
    @Setter
    public static class Labels {
        /** 加工单状态展示 */
        private String statusCreated = "已建档";
        private String statusProcessing = "加工中";
        private String statusReady = "待取镜";
        private String statusPickedUp = "已取镜";
        private String statusCancelled = "已冲销";
        /** 明细行是否返修列的展示 */
        private String reworkYes = "返修";
        private String reworkNo = "—";
    }
}
