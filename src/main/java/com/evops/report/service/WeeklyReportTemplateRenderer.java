package com.evops.report.service;

import com.deepoove.poi.XWPFTemplate;
import com.deepoove.poi.config.Configure;
import com.deepoove.poi.config.ConfigureBuilder;
import com.evops.common.BizCode;
import com.evops.common.BizException;
import com.evops.report.config.ReportProperties;
import com.evops.report.model.WeeklyOrderRow;
import com.evops.report.model.WeeklyReportSnapshot;
import com.evops.report.policy.ZebraLoopRowTablePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import javax.annotation.Resource;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 周报 Word 渲染（poi-tl）。
 * <p>
 * 模板变量名、明细循环标签、列标签全部取自 {@link ReportProperties}——验光师改模板里的
 * 变量名（{{storeCode}} 等）后，在 application.yml 对齐同名配置即可，无需改代码重新打包。
 * <p>
 * 模板路径支持 file: 外置：渲染前懒检查 mtime，替换文件后下次生成即生效（与敏感词库热更一致）。
 * 明细循环用 {@link ZebraLoopRowTablePolicy}：单行式复制数据行并刷隔行底色。
 */
@Component
public class WeeklyReportTemplateRenderer {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportTemplateRenderer.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Resource
    private ResourceLoader resourceLoader;
    @Resource
    private ReportProperties properties;

    /** 上次渲染时的模板文件 mtime（file: 路径热更判定；classpath 恒为 -1） */
    private volatile long lastMtime = -1L;

    /** 渲染一家门店的周报，返回 docx 字节 */
    public byte[] renderDocx(WeeklyReportSnapshot snapshot) {
        byte[] templateBytes = loadTemplateBytes();
        Configure config = buildConfigure();
        Map<String, Object> model = buildModel(snapshot);
        try {
            XWPFTemplate template = XWPFTemplate.compile(
                    new java.io.ByteArrayInputStream(templateBytes), config);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            template.render(model).write(out);
            template.close();
            return out.toByteArray();
        } catch (Exception ex) {
            log.error("周报模板渲染失败 store={} 模板={}", snapshot.getStoreCode(),
                    properties.getTemplatePath(), ex);
            throw new BizException(BizCode.REPORT_TEMPLATE_RENDER_ERROR,
                    BizCode.REPORT_TEMPLATE_RENDER_ERROR.getMessage() + "：" + ex.getMessage());
        }
    }

    private Configure buildConfigure() {
        ConfigureBuilder builder = Configure.builder();
        // 明细循环标签 → 隔行底色循环行策略（标签名可配）
        builder.bind(properties.getTags().getOrders(),
                new ZebraLoopRowTablePolicy(properties.getZebraFill(), properties.getDetailHeaderRow()));
        return builder.build();
    }

    /**
     * 中性快照 → 模板模型：键名全部来自配置。
     * 模板里新增/修改变量只需改配置与模板两处，本方法不依赖任何写死的标签字面量。
     */
    private Map<String, Object> buildModel(WeeklyReportSnapshot snapshot) {
        ReportProperties.Tags tags = properties.getTags();

        List<Map<String, Object>> detail = new ArrayList<>();
        for (WeeklyOrderRow row : snapshot.getRows()) {
            Map<String, Object> item = new HashMap<>();
            item.put(tags.getColumns().getOrderNo(), row.getOrderNo());
            item.put(tags.getColumns().getLensName(), row.getLensName());
            item.put(tags.getColumns().getOrderDate(), row.getOrderDate());
            item.put(tags.getColumns().getStatusText(), row.getStatusText());
            item.put(tags.getColumns().getCustomer(), row.getCustomer());
            item.put(tags.getColumns().getReworkText(), row.getReworkText());
            detail.add(item);
        }

        Map<String, Object> model = new HashMap<>();
        model.put(tags.getStoreCode(), snapshot.getStoreCode());
        model.put(tags.getPeriodStart(), DATE_FMT.format(snapshot.getPeriodStart()));
        model.put(tags.getPeriodEnd(), DATE_FMT.format(snapshot.getPeriodEnd()));
        model.put(tags.getGeneratedAt(), DATE_TIME_FMT.format(snapshot.getGeneratedAt()));
        model.put(tags.getTotalCount(), snapshot.getTotalCount());
        model.put(tags.getReworkCount(), snapshot.getReworkCount());
        model.put(tags.getReworkRate(), snapshot.getReworkRate());
        model.put(tags.getLensSummary(), emptyDash(snapshot.getLensSummary()));
        model.put(tags.getOrders(), detail);
        return model;
    }

    private String emptyDash(String text) {
        return text == null || text.isEmpty() ? "—" : text;
    }

    /** 读模板字节；file: 路径懒检查 mtime，文件变更下次渲染即生效 */
    private byte[] loadTemplateBytes() {
        String path = properties.getTemplatePath();
        try {
            org.springframework.core.io.Resource resource = resourceLoader.getResource(path);
            if (!resource.exists() || !resource.isReadable()) {
                throw new BizException(BizCode.REPORT_TEMPLATE_NOT_FOUND,
                        BizCode.REPORT_TEMPLATE_NOT_FOUND.getMessage() + "：" + path);
            }
            maybeLogExternalChange(resource, path);
            return StreamUtils.copyToByteArray(resource.getInputStream());
        } catch (BizException ex) {
            throw ex;
        } catch (IOException ex) {
            log.error("周报模板读取失败：{}", path, ex);
            throw new BizException(BizCode.REPORT_TEMPLATE_NOT_FOUND,
                    BizCode.REPORT_TEMPLATE_NOT_FOUND.getMessage() + "：" + path);
        }
    }

    private void maybeLogExternalChange(org.springframework.core.io.Resource resource, String path) {
        if (path.startsWith(ResourceLoader.CLASSPATH_URL_PREFIX)) {
            return;
        }
        try {
            File file = resource.getFile();
            long mtime = file.lastModified();
            if (mtime > 0L && mtime != lastMtime) {
                log.info("周报模板{}：{}", lastMtime < 0L ? "已加载" : "变更已热更生效", path);
                lastMtime = mtime;
            }
        } catch (IOException ignore) {
            // 不可取文件句柄（如 jar 内路径）时不做热更判定
        }
    }
}
