package com.evops.report.engine;

import com.deepoove.poi.XWPFTemplate;
import com.deepoove.poi.config.Configure;
import com.deepoove.poi.plugin.table.LoopRowTableRenderPolicy;
import com.evops.common.BizCode;
import com.evops.common.BizException;
import com.evops.report.config.ReportProperties;
import com.evops.report.model.WeeklyReportData;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import javax.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 周汇总报告 Word 渲染器（poi-tl）。
 * <ul>
 *   <li>模板路径 {@code evops.report.weekly-template-path}：classpath: 随包默认；
 *       生产可改 {@code file:/path/xxx.docx} 外部化。file: 路径渲染前懒检查 mtime，
 *       换模板（含增删抬头变量、明细列）即时生效，<b>不用改代码、不用重启</b>。</li>
 *   <li>明细行循环：模板明细表中用 {{@code {{orders}}}} 触发行复制（与首条数据同行），
 *       列内用方括号变量 {@code [orderNo]/[lensName]…}；绑定见下方 Configure。</li>
 *   <li>模板里出现数据中不存在的变量时 poi-tl 默认清空，不报错——故变量可自由增删。</li>
 *   <li>隔行底色：渲染后按数据行第偶数条统一着浅色底（可关、可配色），不依赖模板逐行设置。</li>
 * </ul>
 */
@Component
public class WeeklyReportRenderer {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportRenderer.class);

    /** 明细行集合变量名：模板里 {{orders}} 触发整行循环（代码与模板之间唯一的固定锚点） */
    public static final String DETAIL_LOOP_KEY = "orders";

    private final ResourceLoader resourceLoader;
    private final ReportProperties props;
    private final ObjectMapper objectMapper;
    private final Configure configure;

    /** 当前模板字节快照；file: 路径按 mtime 热替换，classpath: 启动加载一次 */
    private volatile byte[] templateBytes;
    private volatile long templateMtime = -1L;
    private volatile boolean templateLoaded = false;

    public WeeklyReportRenderer(ResourceLoader resourceLoader,
                                ReportProperties props,
                                ObjectMapper objectMapper) {
        this.resourceLoader = resourceLoader;
        this.props = props;
        this.objectMapper = objectMapper;
        // onSameLine=true：{{orders}} 与首行字段同行，模板只需"表头 + 一行数据模板"
        this.configure = Configure.builder()
                .bind(DETAIL_LOOP_KEY, new LoopRowTableRenderPolicy(true))
                .build();
    }

    @PostConstruct
    public void init() {
        try {
            reloadTemplate();
        } catch (Exception ex) {
            // 与词库一致：启动时模板缺失不炸服务，首次导出再按 REPORT_TEMPLATE_INVALID 暴露
            log.error("周汇总报告模板初始加载失败：{}，原因：{}", props.getWeeklyTemplatePath(), ex.getMessage());
        }
    }

    /** 立即重载模板（换模板后可不重启，调用接口触发；file: 路径平时也会按 mtime 自动热更） */
    public synchronized void reloadTemplate() {        String path = props.getWeeklyTemplatePath();
        try {
            Resource resource = resourceLoader.getResource(path);
            if (!resource.exists()) {
                throw new IOException("模板资源不存在：" + path);
            }
            byte[] bytes = StreamUtils.copyToByteArray(resource.getInputStream());
            // 编译期校验：模板损坏立刻暴露，而不是等到真正导出
            XWPFTemplate.compile(new ByteArrayInputStream(bytes), configure).close();
            this.templateBytes = bytes;
            this.templateMtime = mtimeOf(resource);
            this.templateLoaded = true;
            log.info("周汇总报告模板已加载：{}（{} 字节）", path, bytes.length);
        } catch (IOException ex) {
            throw new BizException(BizCode.REPORT_TEMPLATE_INVALID,
                    "周汇总报告模板不可读：" + path + "，" + ex.getMessage());
        } catch (Exception ex) {
            throw new BizException(BizCode.REPORT_TEMPLATE_INVALID,
                    "周汇总报告模板已损坏或变量语法有误：" + path + "，" + ex.getMessage());
        }
    }

    /** 渲染一家门店的周汇总报告，返回 docx 字节 */
    public byte[] render(WeeklyReportData data) {
        byte[] bytes = currentTemplateBytes();
        // 转 Map 与行循环字段按名取值对齐；模板多出的变量由 poi-tl 默认清空
        Map<String, Object> model = objectMapper.convertValue(data,
                new TypeReference<Map<String, Object>>() { });
        try (XWPFTemplate template = XWPFTemplate.compile(new ByteArrayInputStream(bytes), configure)) {
            template.render(model);
            if (props.isZebraStripe()) {
                applyZebra(template.getXWPFDocument(),
                        data.getOrders() == null ? 0 : data.getOrders().size());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            template.write(out);
            return out.toByteArray();
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("周汇总报告渲染失败 store={} 周期={}~{}：{}", data.getStoreCode(),
                    data.getPeriodStart(), data.getPeriodEnd(), ex.getMessage());
            throw new BizException(BizCode.SYSTEM_ERROR, "报告渲染失败：" + ex.getMessage());
        }
    }

    /**
     * 隔行底色：明细表第 0 行为表头（不动），数据行从第 1 行起，
     * 第偶数条数据（表内行索引 2、4、6…）着浅色底。
     */
    private void applyZebra(XWPFDocument doc, int orderCount) {
        List<XWPFTable> tables = doc.getTables();
        int idx = props.getDetailTableIndex();
        if (idx < 0 || idx >= tables.size()) {
            log.warn("明细表序号 {} 超出文档表数量 {}，跳过隔行底色", idx, tables.size());
            return;
        }
        XWPFTable table = tables.get(idx);
        int lastDataRow = Math.min(table.getNumberOfRows(), orderCount + 1);
        for (int rowIdx = 1; rowIdx < lastDataRow; rowIdx++) {
            if (rowIdx % 2 == 0) {
                shadeRow(table.getRow(rowIdx), props.getZebraColor());
            }
        }
    }

    private void shadeRow(XWPFTableRow row, String fill) {
        for (XWPFTableCell cell : row.getTableCells()) {
            CTTcPr pr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
            CTShd shd = pr.isSetShd() ? pr.getShd() : pr.addNewShd();
            shd.setFill(fill);
        }
    }

    /** file: 路径懒热更新：mtime 变化才重载；classpath: 资源用启动快照 */
    private byte[] currentTemplateBytes() {
        String path = props.getWeeklyTemplatePath();
        if (!path.startsWith(ResourceLoader.CLASSPATH_URL_PREFIX)) {
            try {
                File file = resourceLoader.getResource(path).getFile();
                long mtime = file.lastModified();
                if (mtime > 0L && mtime != templateMtime) {
                    reloadTemplate();
                }
            } catch (IOException ex) {
                // 不可读时交给 reloadTemplate/快照处理，这里不刷屏
            }
        }
        if (!templateLoaded || templateBytes == null) {
            // 启动加载失败后的兜底重试
            reloadTemplate();
        }
        return templateBytes;
    }

    private long mtimeOf(Resource resource) {
        try {
            return resource.getFile().lastModified();
        } catch (IOException ex) {
            return -1L;
        }
    }

    public ReportProperties getProps() {
        return props;
    }
}
