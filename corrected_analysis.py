#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
正确提取所有DO160G规则编号（包括QTP-DO160G-XX-X格式）
"""
import openpyxl
import os
import re
from collections import defaultdict

# 读取Excel文件
excel_path = "./prompts/QTP检查单-全部规则-PDDS.xlsx"
rules_dir = "./prompts/DO160G规则/"

print("=" * 100)
print("重新分析：使用正确的正则表达式提取规则")
print("=" * 100)

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

    # 识别分组标题
    if col1 and not col2 and not col3:
        current_section = col1
        continue

    # 识别检查项
    if col1 and col2:
        excel_items.append({
            'section': current_section,
            'number': col1,
            'item': col2,
            'target': col3,
            'row': row_idx
        })

print(f"Excel检查单: {len(excel_items)} 个检查项")

# 按分组统计
sections = defaultdict(list)
for item in excel_items:
    sections[item['section']].append(item)

print("\n" + "=" * 100)
print("读取规则文件并提取所有格式的规则编号")
print("=" * 100)

rule_files_content = {}
rules_by_file = {}
all_rules = []

for filename in sorted(os.listdir(rules_dir)):
    if filename.endswith('.md'):
        filepath = os.path.join(rules_dir, filename)
        with open(filepath, 'r', encoding='utf-8') as f:
            content = f.read()
            rule_files_content[filename] = content

            # 提取所有格式的规则编号
            rules = []

            # 模式1: QTP-XXX-XX 格式（如 QTP-GEN-01, QTP-EQP-01等）
            pattern1 = r'- 规则编号：(QTP-[A-Z]+-\d+)'

            # 模式2: QTP-DO160G-XX-X 格式（如 QTP-DO160G-16-A）
            pattern2 = r'- 规则编号：(QTP-DO160G-\d+-[A-Z])'

            # 合并两种模式
            matches = re.finditer(r'- 规则编号：(QTP-[A-Z0-9]+-[A-Z0-9]+)', content)

            for match in matches:
                rule_id = match.group(1)
                # 提取规则说明
                rule_section = content[match.start():match.start()+500]
                desc_match = re.search(r'规则说明：(.+?)(?=\n\n|###)', rule_section, re.DOTALL)
                if desc_match:
                    rule_desc = desc_match.group(1).strip()
                else:
                    rule_desc = "(未找到说明)"

                rules.append({
                    'id': rule_id,
                    'desc': rule_desc
                })
                all_rules.append({
                    'file': filename,
                    'id': rule_id,
                    'desc': rule_desc
                })

            rules_by_file[filename] = rules
            if rules:
                print(f"\n{filename}: {len(rules)} 个规则")
                for rule in rules:
                    print(f"  {rule['id']}")

print(f"\n" + "=" * 100)
print(f"统计结果")
print("=" * 100)
print(f"总规则数: {len(all_rules)}")
print(f"有规则的文件: {len([f for f in rules_by_file.keys() if len(rules_by_file[f]) > 0])}")
print(f"无规则的文件: {len([f for f in rules_by_file.keys() if len(rules_by_file[f]) == 0])}")

# 生成最终报告
output_path = "./corrected_analysis_report.txt"
with open(output_path, 'w', encoding='utf-8') as f:
    f.write("QTP检查单与DO160G规则文件对应关系 - 修正报告\n")
    f.write("=" * 100 + "\n\n")
    f.write("分析日期: 2026-08-11\n")
    f.write("修正说明: 使用正确的正则表达式提取了所有规则编号（包括QTP-DO160G-XX-X格式）\n\n")

    f.write("=" * 100 + "\n")
    f.write("一、规则文件统计\n")
    f.write("=" * 100 + "\n\n")

    for filename in sorted(rules_by_file.keys()):
        rules = rules_by_file[filename]
        f.write(f"\n【{filename}】 - {len(rules)} 个规则\n")
        f.write("-" * 100 + "\n")
        if rules:
            for rule in rules:
                f.write(f"{rule['id']}\n")
                f.write(f"  {rule['desc'][:100]}...\n\n" if len(rule['desc']) > 100 else f"  {rule['desc']}\n\n")
        else:
            f.write("  (该文件未找到规则编号)\n\n")

    f.write("\n" + "=" * 100 + "\n")
    f.write("二、各试验章节规则统计\n")
    f.write("=" * 100 + "\n\n")

    chapter_files = [f for f in sorted(rules_by_file.keys()) if re.match(r'\d{2}-', f)]
    f.write("DO160G第4-27章规则文件状态:\n\n")

    for filename in chapter_files:
        rules = rules_by_file[filename]
        chapter_num = filename[:2]
        status = "✓" if len(rules) > 0 else "✗"
        f.write(f"{status} {filename:40s} {len(rules):2d} 个规则\n")

    f.write("\n" + "=" * 100 + "\n")
    f.write("三、总结\n")
    f.write("=" * 100 + "\n\n")

    f.write(f"Excel检查单检查项总数: {len(excel_items)}\n")
    f.write(f"规则文件总数: {len(rule_files_content)}\n")
    f.write(f"已编写规则总数: {len(all_rules)}\n")
    f.write(f"有规则的文件数: {len([f for f in rules_by_file.keys() if len(rules_by_file[f]) > 0])}\n")
    f.write(f"无规则的文件数: {len([f for f in rules_by_file.keys() if len(rules_by_file[f]) == 0])}\n\n")

    no_rule_files = [f for f in sorted(rules_by_file.keys()) if len(rules_by_file[f]) == 0]
    if no_rule_files:
        f.write("仍未找到规则的文件:\n")
        for filename in no_rule_files:
            f.write(f"  - {filename}\n")
    else:
        f.write("✓ 所有规则文件都已编写规则！\n")

print(f"\n修正后的分析报告已保存至: {output_path}")
print("=" * 100)
print("分析完成!")
print("=" * 100)
