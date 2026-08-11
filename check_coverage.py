#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
检查Excel检查单中的每一项是否都在规则文件中有对应的规则
"""
import openpyxl
import os
import re
from collections import defaultdict

# 读取Excel文件
excel_path = "./prompts/QTP检查单-全部规则-PDDS.xlsx"
rules_dir = "./prompts/DO160G规则/"

print("=" * 100)
print("分析任务: 检查Excel检查单中的每个检查项是否都在规则文件中有对应")
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

print(f"\nExcel检查单共有 {len(excel_items)} 个检查项")

# 按分组统计
sections = defaultdict(list)
for item in excel_items:
    sections[item['section']].append(item)

print("\n" + "=" * 100)
print("读取所有规则文件内容")
print("=" * 100)

rule_files_content = {}
for filename in sorted(os.listdir(rules_dir)):
    if filename.endswith('.md'):
        filepath = os.path.join(rules_dir, filename)
        with open(filepath, 'r', encoding='utf-8') as f:
            content = f.read()
            rule_files_content[filename] = content

print(f"读取了 {len(rule_files_content)} 个规则文件")

# 生成详细对比报告
output_path = "./excel_vs_rules_mapping.txt"

with open(output_path, 'w', encoding='utf-8') as f:
    f.write("Excel检查单与规则文件的逐项对应关系分析\n")
    f.write("=" * 100 + "\n\n")
    f.write("分析目的: 确认Excel中的每个检查项是否都在规则文件中有明确对应\n\n")

    f.write("=" * 100 + "\n")
    f.write("逐项检查分析\n")
    f.write("=" * 100 + "\n\n")

    coverage_summary = {
        'covered': [],
        'not_covered': [],
        'uncertain': []
    }

    for section, items in sorted(sections.items(), key=lambda x: (x[0] is None, x[0] or '')):
        f.write(f"\n{'=' * 100}\n")
        f.write(f"【{section}】 - {len(items)} 个检查项\n")
        f.write(f"{'=' * 100}\n\n")

        for item in items:
            f.write(f"{item['number']}. {item['item']}\n")
            if item['target']:
                f.write(f"   确认目标: {item['target']}\n")

            # 在规则文件中搜索相关内容
            found_in_files = []
            keywords = [item['item']]
            if item['target']:
                # 提取确认目标中的关键词
                target_keywords = re.findall(r'[一-龥]{2,}', item['target'])
                keywords.extend(target_keywords[:3])  # 取前3个关键词

            for filename, content in rule_files_content.items():
                # 检查是否包含关键词
                matches = 0
                for keyword in keywords:
                    if keyword in content:
                        matches += 1

                if matches >= 1:  # 至少匹配1个关键词
                    found_in_files.append({
                        'file': filename,
                        'matches': matches,
                        'keywords': keywords
                    })

            if found_in_files:
                f.write(f"   ✓ 可能对应的规则文件:\n")
                for match in sorted(found_in_files, key=lambda x: x['matches'], reverse=True)[:3]:
                    f.write(f"      - {match['file']} (匹配度: {match['matches']}/{len(keywords)})\n")
                coverage_summary['covered'].append({
                    'section': section,
                    'item': item['item'],
                    'files': [m['file'] for m in found_in_files]
                })
            else:
                f.write(f"   ✗ 未找到明确对应的规则文件\n")
                coverage_summary['not_covered'].append({
                    'section': section,
                    'item': item['item'],
                    'target': item['target']
                })

            f.write("\n")

    f.write("\n" + "=" * 100 + "\n")
    f.write("统计摘要\n")
    f.write("=" * 100 + "\n\n")

    f.write(f"Excel检查项总数: {len(excel_items)}\n")
    f.write(f"找到对应规则的检查项: {len(coverage_summary['covered'])}\n")
    f.write(f"未找到对应规则的检查项: {len(coverage_summary['not_covered'])}\n")
    f.write(f"覆盖率: {len(coverage_summary['covered'])/len(excel_items)*100:.1f}%\n\n")

    if coverage_summary['not_covered']:
        f.write("\n" + "=" * 100 + "\n")
        f.write("⚠️ 可能遗漏的检查项（未在规则文件中找到明确对应）\n")
        f.write("=" * 100 + "\n\n")

        for item in coverage_summary['not_covered']:
            f.write(f"分组: {item['section']}\n")
            f.write(f"检查项: {item['item']}\n")
            f.write(f"确认目标: {item['target']}\n")
            f.write(f"{'-' * 100}\n\n")

print(f"\n对应关系分析报告已保存至: {output_path}")
print("\n" + "=" * 100)
print("分析完成!")
print("=" * 100)
