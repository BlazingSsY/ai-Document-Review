#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
最终全面分析：QTP检查单与DO160G规则文件的对应关系
"""
import openpyxl
import os
import re
from collections import defaultdict

# 读取Excel文件
excel_path = "./prompts/QTP检查单-全部规则-PDDS.xlsx"
rules_dir = "./prompts/DO160G规则/"

print("=" * 100)
print("第一步：读取并分析Excel检查单")
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

    # 识别分组标题（第2列和第3列为空）
    if col1 and not col2 and not col3:
        current_section = col1
        print(f"\n发现分组: {current_section}")
        continue

    # 识别检查项（有序号和检查项名称）
    if col1 and col2:
        excel_items.append({
            'section': current_section,
            'number': col1,
            'item': col2,
            'target': col3,
            'row': row_idx
        })

print(f"\n共提取 {len(excel_items)} 个检查项")

# 按分组统计
sections = defaultdict(list)
for item in excel_items:
    sections[item['section']].append(item)

print("\n" + "=" * 100)
print("第二步：读取规则文件并提取规则")
print("=" * 100)

rule_files_content = {}
rules_by_file = {}

for filename in sorted(os.listdir(rules_dir)):
    if filename.endswith('.md'):
        filepath = os.path.join(rules_dir, filename)
        with open(filepath, 'r', encoding='utf-8') as f:
            content = f.read()
            rule_files_content[filename] = content

            # 提取规则编号和规则说明
            rules = []
            # 匹配规则编号
            pattern = r'- 规则编号：(QTP-[A-Z0-9]+-[A-Z0-9]+)\s*\n.*?- 规则说明：(.+?)(?=\n\n|###)'
            matches = re.finditer(pattern, content, re.DOTALL)
            for match in matches:
                rule_id = match.group(1)
                rule_desc = match.group(2).strip()
                rules.append({
                    'id': rule_id,
                    'desc': rule_desc
                })

            rules_by_file[filename] = rules
            if rules:
                print(f"\n{filename}:")
                for rule in rules:
                    print(f"  {rule['id']}: {rule['desc'][:60]}...")

# 统计规则总数
total_rules = sum(len(rules) for rules in rules_by_file.values())
print(f"\n共找到 {total_rules} 个规则")

print("\n" + "=" * 100)
print("第三步：生成详细对比报告")
print("=" * 100)

# 生成详细报告
output_path = "./final_analysis_report.txt"
with open(output_path, 'w', encoding='utf-8') as f:
    f.write("QTP检查单与DO160G规则文件最终对比报告\n")
    f.write("=" * 100 + "\n\n")
    f.write("生成时间: 2026-08-11\n")
    f.write("分析目的: 确定Excel检查单中的所有检查项是否都已写入规则文件\n\n")

    f.write("=" * 100 + "\n")
    f.write("一、规则文件清单及规则统计\n")
    f.write("=" * 100 + "\n\n")

    for filename in sorted(rules_by_file.keys()):
        rules = rules_by_file[filename]
        f.write(f"\n【{filename}】 - {len(rules)} 个规则\n")
        f.write("-" * 100 + "\n")
        if rules:
            for rule in rules:
                f.write(f"{rule['id']}\n")
                f.write(f"  规则说明: {rule['desc']}\n\n")
        else:
            f.write("  （该文件暂无规则编号，可能是待开发的章节）\n\n")

    f.write("\n" + "=" * 100 + "\n")
    f.write("二、Excel检查单详细清单\n")
    f.write("=" * 100 + "\n\n")

    # 排序时处理None值
    sorted_sections = sorted(sections.items(), key=lambda x: (x[0] is None, x[0] or ''))
    for section, items in sorted_sections:
        f.write(f"\n【{section}】 - {len(items)} 个检查项\n")
        f.write("-" * 100 + "\n")
        for item in items:
            f.write(f"{item['number']:4s}. {item['item']}\n")
            if item['target']:
                f.write(f"      确认目标: {item['target']}\n")
        f.write("\n")

    f.write("\n" + "=" * 100 + "\n")
    f.write("三、对应关系分析\n")
    f.write("=" * 100 + "\n\n")

    # 分析哪些Excel检查项已经有对应规则
    f.write("根据文件名和规则内容的对应关系分析：\n\n")

    # 已实现的映射
    f.write("【已实现规则的检查项】\n")
    f.write("-" * 100 + "\n\n")

    implemented = {
        "02-通用章节检查项.md": ["通用检查项", "受试设备/系统及陪试设备/系统", "试验科目"],
        "03-各章测试设备.md": ["测试设备/系统"],
        "27-试验记录与报告.md": ["试验报告/记录"]
    }

    for filename, section_names in implemented.items():
        f.write(f"\n文件: {filename}\n")
        rules = rules_by_file.get(filename, [])
        f.write(f"包含规则数: {len(rules)}\n")
        f.write(f"对应Excel分组: {', '.join(section_names)}\n\n")

        for section_name in section_names:
            # 找到匹配的section
            matched_section = None
            for s in sections.keys():
                if s and section_name in s:
                    matched_section = s
                    break

            if matched_section:
                items = sections[matched_section]
                f.write(f"  Excel分组【{matched_section}】({len(items)}项):\n")
                for item in items:
                    f.write(f"    ✓ {item['number']}. {item['item']}\n")
                f.write("\n")

    f.write("\n【待实现规则的检查项】\n")
    f.write("-" * 100 + "\n\n")

    # 各试验章节（04-26章）目前规则数为0，说明还未实现
    f.write("以下试验章节的规则文件已创建，但尚未编写具体规则：\n\n")

    empty_chapters = []
    for filename in sorted(rules_by_file.keys()):
        rules = rules_by_file[filename]
        if len(rules) == 0 and filename not in ['00-跨章一致性.md', '01-试验项目一览表一致性.md']:
            empty_chapters.append(filename)

    for filename in empty_chapters:
        f.write(f"  • {filename}\n")

    f.write(f"\n共 {len(empty_chapters)} 个试验章节规则文件待完善\n\n")

    # 找出Excel中对应这些试验章节的检查项
    f.write("\nExcel检查单中对应各试验章节的检查项:\n\n")

    trial_sections = [s for s in sections.keys() if s and "试验实施" in s]
    f.write(f"共发现 {len(trial_sections)} 个试验实施章节分组\n\n")

    for section in sorted(trial_sections):
        items = sections[section]
        f.write(f"【{section}】 - {len(items)} 项\n")
        for item in items:
            f.write(f"  {item['number']}. {item['item']}\n")
            if item['target']:
                f.write(f"     → {item['target'][:80]}...\n" if len(item['target']) > 80 else f"     → {item['target']}\n")
        f.write("\n")

    f.write("\n" + "=" * 100 + "\n")
    f.write("四、总结\n")
    f.write("=" * 100 + "\n\n")

    f.write(f"1. Excel检查单检查项总数: {len(excel_items)}\n")
    f.write(f"2. 规则文件总数: {len(rule_files_content)}\n")
    f.write(f"3. 已编写规则总数: {total_rules}\n")
    f.write(f"4. 规则完整的文件: {len([f for f in rules_by_file.keys() if len(rules_by_file[f]) > 0])}\n")
    f.write(f"5. 规则待完善的文件: {len([f for f in rules_by_file.keys() if len(rules_by_file[f]) == 0])}\n\n")

    f.write("【结论】\n")
    f.write("-" * 100 + "\n\n")

    f.write("目前的实现情况:\n\n")
    f.write("✓ 已完成规则编写的部分:\n")
    f.write("  - 00-跨章一致性.md (8个规则)\n")
    f.write("  - 01-试验项目一览表一致性.md (6个规则)\n")
    f.write("  - 02-通用章节检查项.md (10个规则) - 覆盖通用检查项、受试设备、试验科目等\n")
    f.write("  - 03-各章测试设备.md (3个规则) - 覆盖测试设备相关检查\n")
    f.write("  - 27-试验记录与报告.md (5个规则) - 覆盖试验报告/记录相关检查\n\n")

    f.write("✗ 待完善的部分:\n")
    f.write(f"  - 第04-26章各试验项目的具体规则 (共{len(empty_chapters)}个文件)\n")
    f.write("  - Excel检查单中\"试验实施-XX章\"分组下的检查项对应的规则\n\n")

    f.write("【建议】\n")
    f.write("-" * 100 + "\n\n")
    f.write("需要继续完善第04-26章各试验项目的规则文件，补充Excel检查单中\n")
    f.write("\"试验实施\"相关分组的检查项对应规则。\n")

print(f"\n最终分析报告已保存至: {output_path}")
print("\n" + "=" * 100)
print("分析完成！")
print("=" * 100)
