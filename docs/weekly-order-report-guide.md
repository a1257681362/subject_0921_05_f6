# 加工单周汇总报告 · 验光师操作手册

> 适用功能：取镜后**返修登记**与**加工单周汇总报告**（poi-tl Word 模板，按门店分别出文件）。
> 报告一句话：选好**统计周期**（按取镜时间）和**门店**，一键导出；单店直接下 `.docx`，多店/全部门店下一个 `.zip`（每家门店一个 docx）。单号、镜款、返修率自动填，明细表隔行底色，不用再手写。
> 接口均需认证（`-u bootstrap:bootstrap`）。写接口带头 `X-Operator-Id`（工号）、`X-Request-No`（每次操作换新号，勿重复）；导出是查询，可带同样的头用于留痕追溯。

> **评审安排：2026-10-01（后天）进行本功能验光师评审。** 评审前请按本手册第三节走一遍"登记返修 → 导出周报 → 改模板变量"。

## 〇、口径与名词

| 名词 | 含义 |
| --- | --- |
| 取镜单 | 状态已流转到 `PICKED_UP`（已取镜）的加工单；报告**只统计周期内取镜的单** |
| 返修登记 | 客户取镜后返修，登记一次留一条记录；**加工单状态保持"已取镜"不变**，不影响既有建档/修改/流转流程 |
| 返修单数 | 周期内取镜、且截至出报告时有过返修登记的加工单数（一单返修多次仍算 1 张） |
| 返修次数 | 返修登记条数之和（一单可返修多次） |
| 返修率 | 返修单数 ÷ 周期取镜总单数，百分比保留两位小数，如 `50.00%` |
| 统计周期 | `start`～`end`，**含首尾两天**，按取镜（PICKUP）业务时间归属 |

返修与取镜状态互不干扰：

```
… READY ──pickup──▶ PICKED_UP 已取镜（终态）
                        │
                        └── 返修可登记 0..N 次（状态始终是 PICKED_UP，只在 t_process_rework 追加记录）
```

## 一、登记返修（取镜后）

只有**已取镜**的加工单能登记返修；未取镜返回 `ORDER_NOT_PICKED_UP`。一单可登记多次。

```bash
# 1) 先把单走到已取镜（沿用既有流转，流程不变）
curl -s -u bootstrap:bootstrap -X POST http://localhost:8080/api/orders/123/pickup \
  -H 'X-Operator-Id: 1001' -H 'X-Request-No: ORD-PICKUP-123'

# 2) 登记返修（reason 选填）
curl -s -u bootstrap:bootstrap -X POST http://localhost:8080/api/orders/123/rework \
  -H 'Content-Type: application/json' \
  -H 'X-Operator-Id: 1001' -H 'X-Request-No: ORD-REWORK-123-1' \
  -d '{"reason":"客户反映镜腿松动"}'
# 预期：code=OK，data.reworkNo 形如 RW-20260929-XXXXXXXXXX
# 同一 X-Request-No 重复提交返回 REQUEST_DUPLICATED（库层兜底）
```

查某张单的返修记录：`GET /api/orders/123` 返回体里的 `reworks` 数组。

## 二、导出周汇总报告

### 第 1 步：单店导出（直接得 docx）

```bash
curl -s -u bootstrap:bootstrap \
  "http://localhost:8080/api/reports/weekly-orders?start=2026-09-21&end=2026-09-27&storeCode=S01" \
  -H 'X-Operator-Id: 1001' -H 'X-Request-No: RPT-2026W39-S01' \
  -o 周汇总-S01.docx
```

### 第 2 步：批量按门店分别出文件（多店 → zip）

```bash
# 指定多家门店：重复 storeCode 参数，每家一个 docx，打成一个 zip
curl -s -u bootstrap:bootstrap -G http://localhost:8080/api/reports/weekly-orders \
  --data-urlencode "start=2026-09-21" --data-urlencode "end=2026-09-27" \
  --data-urlencode "storeCode=S01" --data-urlencode "storeCode=S02" \
  -H 'X-Operator-Id: 1001' -H 'X-Request-No: RPT-2026W39-BATCH' \
  -o 周汇总-批量.zip

# 不传 storeCode = 周期内有取镜数据的"全部门店"，同样每家一个 docx 打 zip
curl -s -u bootstrap:bootstrap -G http://localhost:8080/api/reports/weekly-orders \
  --data-urlencode "start=2026-09-21" --data-urlencode "end=2026-09-27" \
  -H 'X-Operator-Id: 1001' -H 'X-Request-No: RPT-2026W39-ALL' \
  -o 周汇总-全部门店.zip
```

zip 内文件名形如 `加工单周汇总_S01_20260921-20260927.docx`，中文文件名，常见解压软件可直接打开。

### 常见返回

| code | 含义 / 处理 |
| --- | --- |
| `REPORT_NO_DATA` | 周期内（指定门店）没有取镜单；换周期或门店 |
| `REPORT_PERIOD_INVALID` | 止日早于起日，或周期天数超过上限（默认 93 天，`evops.report.max-period-days` 可调） |
| `REPORT_TEMPLATE_INVALID` | 模板文件缺失/损坏，见第三节，或联系 IT |

每次导出在 `t_report_export_log` 追加一行（批次号、周期、门店、文件名、操作人、时间），只追加可追溯。

## 三、改模板：增删变量/明细列不用改代码

模板是一个普通 Word 文件，默认随程序打包：

```
src/main/resources/templates/weekly-orders.docx
```

### 模板里的写法

- 普通变量（抬头/汇总）：双花括号，如 `{{storeCode}}`、`{{reworkRate}}`。
- 明细表行循环：数据行**第一个格子**写 `{{orders}}` 触发整行复制；同一行各列用**方括号**取本行字段，如 `[orderNo]`、`[lensName]`。这一行在导出时按加工单一单一行展开。
- 模板里写了数据中**不存在的变量**，导出时该处自动留空、**不报错**——所以随便加列/加抬头变量，代码一行都不用动；想让新变量有值，再让 IT 把字段加进数据即可。
- 明细表**隔行底色不用在模板里刷**：导出程序统一给第偶数条数据行着浅色底（表头深蓝底在模板里）。

### 当前可用变量

抬头/汇总：`storeCode`、`periodStart`、`periodEnd`、`generatedAt`、`orderCount`（取镜总数）、`reworkOrderCount`（返修单数）、`reworkTotalCount`（返修次数）、`reworkRate`（返修率）。

明细行（`orders` 循环内）：`orderNo` 加工单号、`storeCode` 门店、`lensName` 镜款、`lensCode` 镜款编码、`orderStatus` 当前状态、`pickupDate` 取镜日期、`reworkCount` 返修次数、`reworkFlag` 是否返修（是/否）。

### 三种改法

1. **直接用 Word 改** docx（加列、改表头、调字号都行），保存即可。开发环境改的是 `src/main/resources/templates/weekly-orders.docx`，重新构建后随包生效。
2. **重建默认模板**：版式要大改时改 `scripts/TemplateBuilder.java`，然后：

   ```bash
   sh scripts/build-weekly-template.sh
   ```

3. **生产外部化（推荐门店自行改版用）**：把配置指向外部文件：

   ```yaml
   evops:
     report:
       weekly-template-path: file:/data/evops/templates/weekly-orders.docx
   ```

   外部文件**改完即生效**：程序每次导出前比对文件修改时间，变了自动重载，**不用重启、不用改代码**。也可手动立即重载：

   ```bash
   curl -s -u bootstrap:bootstrap -X POST http://localhost:8080/api/reports/weekly-template/reload
   curl -s -u bootstrap:bootstrap      http://localhost:8080/api/reports/weekly-template/info
   ```

### 隔行底色开关

```yaml
evops:
  report:
    zebra-stripe: true        # false 关闭隔行底色
    zebra-color: F2F2F2       # 十六进制 RGB（不带 #）
    detail-table-index: 0     # 明细表是文档里第几张表（从 0 起；默认模板只有一张）
```

## 四、评审自查清单（2026-10-01 评审用）

- [ ] 未取镜的单登记返修被拒（`ORDER_NOT_PICKED_UP`）；取镜后可登记，状态仍是"已取镜"。
- [ ] 同一单登记两次返修都成功；同一请求号重试返回 `REQUEST_DUPLICATED`。
- [ ] 单店导出得到 docx：加工单号、镜款、返修次数、返修率已自动填，无 `{{` 残留。
- [ ] 明细表隔行有浅色底、表头深蓝底。
- [ ] 指定两家门店导出得到 zip，里面恰好两个 docx；不传门店覆盖全部有数据门店。
- [ ] 周期外取镜、未取镜的单不进报告。
- [ ] 在外部 file: 模板里加一个数据中不存在的变量（如 `{{storeManager}}`）再导出：该处留空、报告正常，且改完无需重启即生效。
