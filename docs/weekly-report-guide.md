# 加工单汇总周报 · 验光师使用指南

> 适用功能：验光师每周提交加工单汇总报告（Word），含加工单明细、**返修率**、**镜款分布**；
> 支持**批量按门店分别出文件**（每店一个 docx，打包 ZIP）。
> 变更单（改方/换镜款评审）流程见第三节；加工单建档/修改/取镜流程不变。
> 接口均需认证（`-u bootstrap:bootstrap`），写/导出接口带头 `X-Operator-Id`、`X-Request-No`（每次操作换新号）、`X-Biz-Time`（可选）。

## 〇、统计口径（先对齐，再看数字）

| 指标 | 口径 |
| --- | --- |
| 统计周期 | 加工单**建档时间**落在 `periodStart 00:00:00 ~ periodEnd 23:59:59`（含首尾，日期参数 `yyyy-MM-dd`） |
| 加工单总数 | 周期内本门店建档、**且未冲销**（不含 CANCELLED）的加工单数 |
| 返修单 | 周期内本门店有**评审通过**变更单（`REWRITE` 改方 / `ALTER` 换镜款）的加工单，按加工单去重；待评审、被驳回的不算 |
| 返修率 | 返修单数 ÷ 加工单总数，百分比保留两位小数（如 `33.33%`）；无单时 `0.00%` |
| 镜款分布 | 按加工单所用镜片名称计数、数量降序，形如 `1.60非球面×8；1.67非球面×3` |
| 客户列 | 报告中客户姓名打码仅留首字（如 `王**`），底账原文仍在库内按权限可查 |

## 一、先看口径，再出文件（推荐）

导出前先用 JSON 预览核对数字，预览**不生成文件、不留痕**：

```bash
curl -s -u bootstrap:bootstrap -G http://localhost:8080/api/reports/weekly/preview \
  --data-urlencode 'storeCode=S01' \
  --data-urlencode 'periodStart=2026-09-22' \
  --data-urlencode 'periodEnd=2026-09-28'
# data：totalCount / reworkCount / reworkRate / lensSummary / rows[]
```

## 二、出周报

### 单店 Word

```bash
curl -s -u bootstrap:bootstrap -G http://localhost:8080/api/reports/weekly/docx \
  --data-urlencode 'storeCode=S01' \
  --data-urlencode 'periodStart=2026-09-22' \
  --data-urlencode 'periodEnd=2026-09-28' \
  -o 加工单周报_S01.docx
```

### 批量按门店分别出文件（ZIP）

```bash
# 指定门店集合：每家门店一个 docx，打成一个 ZIP
curl -s -u bootstrap:bootstrap -X POST http://localhost:8080/api/reports/weekly/batch \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Id: 9101' -H 'X-Request-No: RPT-W39-S01S02' \
  -d '{"storeCodes":["S01","S02"],"periodStart":"2026-09-22","periodEnd":"2026-09-28"}' \
  -D headers.txt -o 周报批量.zip
# 响应头：X-Batch-No（批次号，同批各店留痕共享）、X-File-Count（条目数）、X-Failed-Stores（跳过门店）

# 不传 storeCodes = 周期内有建档数据的全部门店，各出一个 docx
curl -s -u bootstrap:bootstrap -X POST http://localhost:8080/api/reports/weekly/batch \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Id: 9101' -H 'X-Request-No: RPT-W39-ALL' \
  -d '{"periodStart":"2026-09-22","periodEnd":"2026-09-28"}' -o 周报全部门店.zip
```

- 即使某门店周期内**无单也会出一个空表文件**（总数 0、返修率 0.00%、明细空），保证"按店各一份"不漏店。
- 单店渲染失败只跳过该店（记入 `X-Failed-Stores`），其余门店文件照常产出；每次按店导出在 `t_weekly_report_log` 留一行（只追加）。
- 批量各店口径可先预览（不落痕）：`POST /api/reports/weekly/batch/preview`，body 同上。
- ZIP 条目文件名形如 `加工单周报_S01_20260922_20260928.docx`。

## 三、变更单（返修数据的来源，也是改方/换镜款的评审流程）

变更单**不直接改加工单**：验光师提交 → 安排评审 → 评审通过后，仍走既有 `PUT /api/orders` 落库。

```bash
# 1) 提交变更单（changeType：REWRITE 改方 / ALTER 换镜款）
curl -s -u bootstrap:bootstrap -X POST http://localhost:8080/api/order-changes \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Id: 9102' -H 'X-Request-No: CHG-2026W39-001' \
  -d '{"changeNo":"C-20260928-001","orderNo":"O-20260928-0007","changeType":"REWRITE","reason":"复检度数偏差0.50D"}'

# 2) 安排验光师评审（例：安排在后天上午）
curl -s -u bootstrap:bootstrap -X POST http://localhost:8080/api/order-changes/C-20260928-001/schedule \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Id: 9102' -H 'X-Request-No: CHG-2026W39-S1' \
  -d '{"reviewTime":"2026-10-01T10:00:00"}'

# 3) 录入评审结论（须先安排评审；approved=true 通过 / false 驳回，可带意见）
curl -s -u bootstrap:bootstrap -X POST http://localhost:8080/api/order-changes/C-20260928-001/review \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Id: 9200' -H 'X-Request-No: CHG-2026W39-R1' \
  -d '{"approved":true,"reviewComment":"同意改方"}'
```

约束：同一加工单至多一张**待评审**变更单（重复提交 `ORDER_CHANGE_ALREADY_PENDING`）；同一请求号重复提交由库层兜底（`REQUEST_DUPLICATED`）；驳回后要再申请，另开一张新变更单。

## 四、Word 模板长什么样

随包模板：`src/main/resources/report/templates/weekly-order-summary.docx`（表格结构）：

```
加工单汇总报告（标题）
门店：{{storeCode}}    统计周期：{{periodStart}} 至 {{periodEnd}}    生成时间：{{generatedAt}}
加工单总数：{{totalCount}}    返修单数：{{reworkCount}}    返修率：{{reworkRate}}
镜款分布：{{lensSummary}}

加工单明细
| 加工单号 | 镜款 | 开单日期 | 状态 | 客户 | 返修 |   ← 表头（蓝底）
|{{orders}}[orderNo]|[lensName]|[orderDate]|[statusText]|[customer]|[reworkText]|  ← 数据模板行
```

- 双花括号 `{{...}}` 是普通变量；明细表数据模板行首格的 `{{orders}}` 是**循环标签**，
  该行会按明细一张单复制一行，行内方括号 `[...]` 是列标签。
- **隔行底色**（第 2、4、6… 条数据行浅蓝底）由程序渲染时统一刷，不用在模板里手工设。

### 模板变量字典

| 模板写法 | 含义 | 配置键（application.yml） |
| --- | --- | --- |
| `{{storeCode}}` | 门店编号 | `evops.report.tags.store-code` |
| `{{periodStart}}` / `{{periodEnd}}` | 周期起止（yyyy-MM-dd） | `tags.period-start` / `tags.period-end` |
| `{{generatedAt}}` | 生成时间（yyyy-MM-dd HH:mm） | `tags.generated-at` |
| `{{totalCount}}` / `{{reworkCount}}` / `{{reworkRate}}` | 总数 / 返修单数 / 返修率 | `tags.total-count` 等 |
| `{{lensSummary}}` | 镜款分布 | `tags.lens-summary` |
| `{{orders}}` | 明细表循环标签 | `tags.orders` |
| `[orderNo]` `[lensName]` `[orderDate]` `[statusText]` `[customer]` `[reworkText]` | 明细六列 | `tags.columns.*` |

展示口径词（状态中文、返修/—）在 `evops.report.labels.*`。

## 五、改模板：变量改名/加列，不用改 Java 代码

1. 复制一份模板，在 Word 里调整版式、改变量名或增删列。
2. **注意 run 边界**：数据模板行首格必须保留 `{{orders}}` 与 `[orderNo]` 两个标签，且它们要在**不同的文本 run**（Word 里可在两标签间敲个空格再删掉，或干脆用脚本重建）。普通变量 `{{xxx}}` 整体不要被拆成多段（Word 自动纠错有时会拆，发现渲染不出来时删掉重打一遍）。
3. 把改过的变量名在 `application.yml` 的 `evops.report.tags` 对齐。例如模板把门店变量写成 `{{menDian}}`、循环写成 `{{mingXi}}`、单号列写成 `[danHao]`：

   ```yaml
   evops:
     report:
       tags:
         orders: mingXi
         store-code: menDian
         columns:
           order-no: danHao
   ```

4. 模板结构若改动较大，可改 `scripts/gen-weekly-template.py` 后**幂等重跑**重建随包模板：

   ```bash
   python3 scripts/gen-weekly-template.py
   ```

### 外置模板与热更（生产推荐）

```yaml
evops:
  report:
    template-path: file:/data/evops/templates/weekly-order-summary.docx
    zebra-fill: F2F6FB        # 隔行底色，留空不上色
    detail-header-row: 1      # 表头行数（隔行上色从其后开始）
```

指向 `file:` 后，替换模板文件**下次生成即生效**（渲染前懒检查文件 mtime，无需重启）；
`classpath:` 随包模板则随发版更新。模板缺失/不可读返回 `REPORT_TEMPLATE_NOT_FOUND`，
模板内标签写错导致渲染失败返回 `REPORT_TEMPLATE_RENDER_ERROR`（看日志里的具体标签提示）。
