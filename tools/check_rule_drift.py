#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""核验 prompts/DO160G规则/*.md 与数据库中实际生效的规则是否一致。

背景：规则靠人工上传 .md 入库（POST /api/v1/rules/upload），仓库里的规则文件与
库中实际生效的规则之间没有任何自动同步或校验。两者一旦分叉，改文件不会改变审查
行为，而两次审查即使规则编号相同也可能用的是不同版本的判据——审查结果因此不可比。

本脚本把这种分叉变成可检测、可门禁的：有漂移时以退出码 1 结束。

用法：
    python tools/check_rule_drift.py                     # 用 docker exec 连容器里的库
    python tools/check_rule_drift.py --psql              # 用本机 psql
    python tools/check_rule_drift.py --scope QTP-DO160G  # 只比对某一族
"""
from __future__ import unicode_literals

import argparse
import glob
import io
import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RULE_DIR = os.path.join(REPO, "prompts", "DO160G规则")

DB_CONTAINER = os.environ.get("AI_REVIEW_DB_CONTAINER", "ai-review-db")
DB_USER = os.environ.get("AI_REVIEW_DB_USER", "postgres")
DB_NAME = os.environ.get("AI_REVIEW_DB_NAME", "ai_review")

FIELD_SEP = "\x1f"
ROW_SEP = "\x1e"


def normalize(text):
    """比对前归一化：只忽略行尾空白与空行，其余一律视为实质差异。"""
    if text is None:
        return ""
    lines = [line.rstrip() for line in text.replace("\r\n", "\n").split("\n")]
    return "\n".join(line for line in lines if line).strip()


def load_md_rules():
    """解析规则文件，返回 {rule_code: (rule_name, content, source_file)}。

    规则块以 '## NN. 名称' 开头，以下一个同级标题或文件结束为界；
    '- 规则编号：X' 出现在块内。
    """
    rules = {}
    for path in sorted(glob.glob(os.path.join(RULE_DIR, "*.md"))):
        if os.path.basename(path).upper() == "README.MD":
            continue
        text = io.open(path, encoding="utf-8").read().replace("\r\n", "\n")
        # 以 '## ' 一级规则标题切块
        blocks = re.split(r"(?m)^##\s+", text)
        for block in blocks[1:]:
            head, _, body = block.partition("\n")
            m = re.match(r"\d+\.\s*(.+?)\s*$", head)
            if not m:
                continue
            name = m.group(1)
            code = re.search(r"(?m)^-\s*规则编号：\s*(\S+)\s*$", body)
            if not code:
                continue
            rules[code.group(1)] = (name, normalize(body), os.path.basename(path))
    return rules


def load_db_rules(use_psql):
    sql = (
        "COPY (SELECT rule_code, rule_name, coalesce(content,'') "
        "FROM rules WHERE rule_code IS NOT NULL "
        "ORDER BY rule_code) TO STDOUT "
        "WITH (FORMAT text, DELIMITER E'\\x1f', NULL '')"
    )
    if use_psql:
        cmd = ["psql", "-U", DB_USER, "-d", DB_NAME, "-A", "-t", "-c", sql]
    else:
        cmd = ["docker", "exec", DB_CONTAINER, "psql", "-U", DB_USER,
               "-d", DB_NAME, "-A", "-t", "-c", sql]
    proc = subprocess.run(cmd, capture_output=True)
    if proc.returncode != 0:
        sys.stderr.write("查询数据库失败：\n" + proc.stderr.decode("utf-8", "replace") + "\n")
        sys.exit(2)

    out = proc.stdout.decode("utf-8", "replace")
    rules = {}
    for line in out.split("\n"):
        if FIELD_SEP not in line:
            continue
        parts = line.split(FIELD_SEP)
        if len(parts) < 3:
            continue
        code, name, content = parts[0], parts[1], FIELD_SEP.join(parts[2:])
        # COPY TEXT 会把换行转义成 \n，还原后再归一化
        content = content.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\")
        rules[code.strip()] = (name.strip(), normalize(content))
    return rules


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--psql", action="store_true", help="用本机 psql 而非 docker exec")
    ap.add_argument("--scope", default="", help="只比对以该前缀开头的 rule_code")
    ap.add_argument("--quiet", action="store_true", help="只输出汇总")
    args = ap.parse_args()

    md = load_md_rules()
    db = load_db_rules(args.psql)
    if args.scope:
        md = {k: v for k, v in md.items() if k.startswith(args.scope)}
        db = {k: v for k, v in db.items() if k.startswith(args.scope)}

    only_md = sorted(set(md) - set(db))
    only_db = sorted(set(db) - set(md))
    both = sorted(set(md) & set(db))

    name_diff = [c for c in both if md[c][0] != db[c][0]]
    body_diff = [c for c in both if md[c][0] == db[c][0] and md[c][1] != db[c][1]]

    def emit(title, items, render):
        if not items:
            return
        print("\n=== %s（%d 条）===" % (title, len(items)))
        if args.quiet:
            return
        for c in items:
            print(render(c))

    print("规则文件 %d 条 ｜ 数据库 %d 条 ｜ 交集 %d 条" % (len(md), len(db), len(both)))

    emit("仅规则文件有：库中不存在，改文件不会影响审查", only_md,
         lambda c: "  %-24s %-34s (%s)" % (c, md[c][0][:32], md[c][2]))
    emit("仅数据库有：无法通过改文件维护，改动会在下次上传时被覆盖", only_db,
         lambda c: "  %-24s %s" % (c, db[c][0][:32]))
    emit("规则名不同：同一编号指向了不同的规则", name_diff,
         lambda c: "  %-24s 文件=%-26s 库=%s" % (c, md[c][0][:24], db[c][0][:24]))
    emit("规则名相同但正文不同：库中是另一版本的判据", body_diff,
         lambda c: "  %-24s %-30s (%s)" % (c, md[c][0][:28], md[c][2]))

    drift = len(only_md) + len(only_db) + len(name_diff) + len(body_diff)
    print("\n" + ("未发现漂移，规则文件与数据库一致。" if drift == 0
                  else "发现 %d 处漂移。库中生效的判据与仓库规则文件不一致，"
                       "两次审查的结果不可比。" % drift))
    if drift:
        print("处理方式：确认规则文件为准后，通过 POST /api/v1/rules/upload"
              "（replaceExisting=true）重新导入对应的 .md，再重跑本脚本确认归零。")
    return 1 if drift else 0


if __name__ == "__main__":
    sys.exit(main())
