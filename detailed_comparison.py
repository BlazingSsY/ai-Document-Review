#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
详细对比QTP检查单与DO160G规则文件
"""
import openpyxl
import os
import re
from collections import defaultdict

# 读取Excel文件
excel_path = "./prompts/QTP检查单-全部规则-PDDS.xlsx"
rules_dir = "./prompts/DO160G规则/"

print("正在读取Excel文件...")
wb = openpyxl.load_workbook(excel_path)
ws = wb.active

# 收集Excel中的所有检查项
excel_items = []
current_section = None

for row_idx, row in enumerate(ws.iter_rows(min_row=2, values_only=True), start=2):
    if not any(cell for cell in row):
        continue

    col1 = str(row[0]).strip() if row[0] else ""
    col2 = str(row[1]).strip() if row[1] else ""
    col3 = str(row[2]).strip() if row[2] else ""

    # 识别分组标题（第2列和第3列为空）
    if col1 and not col2 and not col3:
        current_section = col1
        continue

    # 识别检查项（有序号和检查项名称）
    if col1.isdigit() and col2:
        excel_items.append({
            'section': current_section,
            'number': col1,
            'item': col2,
            'target': col3,
            'row': row_idx
        })

print(f"从Excel中提取了 {len(excel_items)} 个检查项\n")

# 按分组统计
sections = defaultdict(list)
for item in excel_items:
    sections[item['section']].append(item)

print("=" * 100)
print("Excel检查单结构:")
print("=" * 100)
for section, items in sections.items():
    print(f"\n【{section}】 - {len(items)} 个检查项")
    for item in items[:3]:  # 只显示前3个
        print(f"  {item['number']}. {item['item']}")
        if item['target']:
            print(f"     → {item['target'][:50]}...")
    if len(items) > 3:
        print(f"  ... 还有 {len(items) - 3} 个检查项")

# 读取所有规则文件
print("\n" + "=" * 100)
print("读取规则文件并提取规则编号...")
print("=" * 100)

rule_files_content = {}
all_rule_ids = []

for filename in sorted(os.listdir(rules_dir)):
    if filename.endswith('.md'):
        filepath = os.path.join(rules_dir, filename)
        with open(filepath, 'r', encoding='utf-8') as f:
            content = f.read()
            rule_files_content[filename] = content

            # 提取规则编号
            rule_ids = re.findall(r'规则编号：(QTP-[A-Z]+-\d+)', content)
            all_rule_ids.extend(rule_ids)

            print(f"{filename:35s} - 包含 {len(rule_ids)} 个规则: {', '.join(rule_ids)}")

print(f"\n共找到 {len(all_rule_ids)} 个规则编号")

# 分析对应关系
print("\n" + "=" * 100)
print("分析Excel检查项与规则文件的对应关系")
print("=" * 100)

# 手动定义Excel检查项到规则文件的映射关系
excel_to_rules_mapping = {
    "通用检查项（待补充)": {
        "试验人员": "QTP-GEN-01",
        "试验承试单位": "QTP-GEN-01",
        "通用要求": "QTP-GEN-02",
        "试验连接图/布置图描述": "QTP-GEN-03",
        "试验连接图/布置图检查": "QTP-GEN-03",
    },
    "受试设备/系统及陪试设备/系统": {
        "设备中文名称": "QTP-GEN-04",
        "供应商件号": "QTP-GEN-04",
        "产品构型": "QTP-GEN-04",
        "设备安装区域：": "QTP-GEN-04",
        "受试设备功能描述": "QTP-GEN-05",
        "受试设备交联关系示意": "QTP-GEN-05",
        "受试设备工作状态": "QTP-GEN-05",
        "试验线缆": "QTP-GEN-06",
        "陪试设备描述": "QTP-GEN-07",
        "电搭接": "QTP-GEN-06",
    },
    "试验科目": {
        "试验项": "QTP-GEN-08",
        "试验等级": "QTP-GEN-08",
        "应用类别": "QTP-GEN-08",
        "试验顺序": "QTP-GEN-09",
        "试验件": "QTP-GEN-09",
    },
    "测试设备/系统": {
        "测试设备": "QTP-EQP-01",
        "完整性检查": "QTP-EQP-02",
        "校准周期": "QTP-EQP-03",
        "精度/量程替代方案": "QTP-EQP-03",
        "一致性要求": "QTP-EQP-03",
    }
}

# 生成详细对比报告
output_path = "./detailed_comparison_report.txt"
with open(output_path, 'w', encoding='utf-8') as f:
    f.write("QTP检查单与DO160G规则文件详细对比报告\n")
    f.write("=" * 100 + "\n\n")

    f.write("一、Excel检查单结构 (共 {} 个检查项)\n".format(len(excel_items)))
    f.write("-" * 100 + "\n\n")

    for section, items in sections.items():
        f.write(f"【{section}】 - {len(items)} 个检查项\n")
        for item in items:
            f.write(f"  {item['number']:3s}. {item['item']}\n")
            if item['target']:
                f.write(f"       确认目标: {item['target']}\n")
        f.write("\n")

    f.write("\n" + "=" * 100 + "\n")
    f.write("二、规则文件中的规则编号 (共 {} 个)\n".format(len(all_rule_ids)))
    f.write("-" * 100 + "\n\n")

    for filename in sorted(rule_files_content.keys()):
        content = rule_files_content[filename]
        rule_ids = re.findall(r'规则编号：(QTP-[A-Z]+-\d+)', content)
        if rule_ids:
            f.write(f"{filename}:\n")
            for rule_id in rule_ids:
                # 提取规则说明
                match = re.search(rf'{rule_id}.*?规则说明：(.+)', content, re.MULTILINE)
                if match:
                    desc = match.group(1).strip()
                    f.write(f"  {rule_id}: {desc}\n")
            f.write("\n")

    f.write("\n" + "=" * 100 + "\n")
    f.write("三、Excel检查项与规则文件的映射分析\n")
    f.write("-" * 100 + "\n\n")

    # 统计已映射和未映射的检查项
    mapped_items = set()
    for section, items in sections.items():
        f.write(f"\n【{section}】\n")
        for item in items:
            item_name = item['item']
            if section in excel_to_rules_mapping and item_name in excel_to_rules_mapping[section]:
                rule_id = excel_to_rules_mapping[section][item_name]
                mapped_items.add((section, item_name))
                f.write(f"  ✓ {item['number']}. {item_name:30s} → {rule_id}\n")
            else:
                f.write(f"  ✗ {item['number']}. {item_name:30s} → 【未找到对应规则】\n")

    f.write("\n\n" + "=" * 100 + "\n")
    f.write("四、统计摘要\n")
    f.write("-" * 100 + "\n\n")
    f.write(f"Excel检查项总数: {len(excel_items)}\n")
    f.write(f"已映射检查项数: {len(mapped_items)}\n")
    f.write(f"未映射检查项数: {len(excel_items) - len(mapped_items)}\n")
    f.write(f"规则文件规则数: {len(all_rule_ids)}\n")
    f.write(f"规则文件总数: {len(rule_files_content)}\n")

print(f"\n详细对比报告已保存至: {output_path}")
print("\n" + "=" * 100)
print("分析完成!")
print("=" * 100)
