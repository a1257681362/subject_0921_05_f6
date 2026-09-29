#!/bin/sh
# 生成（或重新生成）加工单周汇总报告 Word 模板：
#   src/main/resources/templates/weekly-orders.docx
#
# 什么时候用：
#   1) 首次拿到工作区、模板缺失时；
#   2) 要改模板的固定版式（标题、表头、列顺序、列宽、配色）时——改 scripts/TemplateBuilder.java 后重跑本脚本；
#   日常仅"增删变量/明细列"也可以直接在 Word 里改 docx，不必动代码。
#
# 用法: sh scripts/build-weekly-template.sh [输出 docx 路径]
# 依赖: 已执行过 mvn（本地仓库有 poi 依赖）；脚本会自动拼装 classpath。

set -e

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
cd "$ROOT_DIR"

OUT="${1:-src/main/resources/templates/weekly-orders.docx}"
BUILD_DIR="target/template-builder"
CP_FILE="target/template-builder.classpath"

echo ">> 拼装依赖 classpath ..."
mvn -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE"
# 已编译的主类目录（含 poi-tl 等也在 classpath 文件里）
CP="target/classes:$(cat "$CP_FILE")"

mkdir -p "$BUILD_DIR"
echo ">> 编译 TemplateBuilder ..."
javac -encoding UTF-8 -cp "$CP" -d "$BUILD_DIR" scripts/TemplateBuilder.java

echo ">> 生成模板 -> $OUT"
java -cp "$BUILD_DIR:$CP" TemplateBuilder "$OUT"

echo ">> 完成。模板变量说明见 docs/weekly-order-report-guide.md"
