import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TMP = ROOT / ".codex_tmp" / "audit"
TMP.mkdir(parents=True, exist_ok=True)


def clean(value):
    if value is None:
        return ""
    return re.sub(r"\s+", " ", str(value)).strip()


workbook = json.loads((ROOT / ".codex_tmp/qtp_workbook/qtp-values.json").read_text(encoding="utf-8"))
workbook_lines = []
for sheet in workbook:
    workbook_lines.append(f"\n# 工作表：{sheet['name']}")
    for idx, row in enumerate(sheet["values"], start=1):
        cells = [clean(v) for v in row]
        populated = [f"{chr(65+i)}={v}" for i, v in enumerate(cells) if v]
        if populated:
            workbook_lines.append(f"R{idx}: " + " | ".join(populated))
(TMP / "qtp_rows.md").write_text("\n".join(workbook_lines), encoding="utf-8")


rule_entries = []
rules_dir = ROOT / "prompts/DO160G规则"
for path in sorted(rules_dir.glob("*.md")):
    if path.name.lower() == "readme.md":
        continue
    text = path.read_text(encoding="utf-8")
    matches = list(re.finditer(r"(?m)^##\s+(\d{2})\.\s+(.+?)\s*$", text))
    for i, match in enumerate(matches):
        block_end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        block = text[match.start():block_end].strip()
        code_m = re.search(r"(?m)^-\s*规则编号：\s*(\S+)\s*$", block)
        type_m = re.search(r"(?m)^-\s*规则类型：\s*(.+?)\s*$", block)
        explain_m = re.search(r"(?m)^-\s*规则说明：\s*(.+?)\s*$", block)
        keywords_m = re.search(r"(?m)^-\s*关键词：\s*(.+?)\s*$", block)
        numerics = sorted(set(re.findall(
            r"(?<![A-Za-z0-9])(?:[+-]?\d+(?:\.\d+)?(?:\s*[~～—－-]\s*[+-]?\d+(?:\.\d+)?)?\s*(?:℃|°C|VDC|VAC|kV|V|mA|A|Hz|kHz|MHz|GHz|Ω|pF|μs|ms|s|min|h|小时|分钟|米|m|mm|英尺|g|G|%|次|个|循环|dB|dBμV|dBµV|W|kW))",
            block,
        )))
        rule_entries.append({
            "file": path.name,
            "ordinal": match.group(1),
            "title": match.group(2).strip(),
            "code": code_m.group(1) if code_m else "",
            "type": clean(type_m.group(1)) if type_m else "",
            "keywords": clean(keywords_m.group(1)) if keywords_m else "",
            "description": clean(explain_m.group(1)) if explain_m else "",
            "numerics": numerics,
            "block": block,
        })
(TMP / "rule_entries.json").write_text(json.dumps(rule_entries, ensure_ascii=False, indent=2), encoding="utf-8")

summary = []
by_file = {}
for entry in rule_entries:
    by_file.setdefault(entry["file"], []).append(entry)
for file_name, entries in by_file.items():
    summary.append(f"\n# {file_name}（{len(entries)}条）")
    for e in entries:
        nums = "；".join(e["numerics"])
        summary.append(f"- {e['code']}｜{e['title']}｜{e['description']}｜数值：{nums}")
(TMP / "rule_summary.md").write_text("\n".join(summary), encoding="utf-8")

codes = [e["code"] for e in rule_entries if e["code"]]
duplicate_codes = sorted({code for code in codes if codes.count(code) > 1})
missing_codes = [f"{e['file']}::{e['ordinal']} {e['title']}" for e in rule_entries if not e["code"]]
print(json.dumps({
    "workbook_sheets": {s["name"]: len(s["values"]) for s in workbook},
    "rule_files": len(by_file),
    "rule_entries": len(rule_entries),
    "duplicate_codes": duplicate_codes,
    "missing_codes": missing_codes,
}, ensure_ascii=False, indent=2))
