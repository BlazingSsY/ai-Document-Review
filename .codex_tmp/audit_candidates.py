import json
import re
import unicodedata
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AUDIT = ROOT / ".codex_tmp/audit"
workbook = json.loads((ROOT / ".codex_tmp/qtp_workbook/qtp-values.json").read_text(encoding="utf-8"))
rules = json.loads((AUDIT / "rule_entries.json").read_text(encoding="utf-8"))
standard = (ROOT / "prompts/RTCA DO-160G中文版.md").read_text(encoding="utf-8")


def norm(text):
    text = unicodedata.normalize("NFKC", text or "")
    text = text.replace("−", "-").replace("–", "-").replace("—", "-").replace("－", "-")
    text = text.replace("~", "-").replace("～", "-")
    text = text.replace("摄氏度", "℃").replace("°C", "℃")
    text = re.sub(r"\s+", "", text.lower())
    return text


def numeric_tokens(text):
    raw = unicodedata.normalize("NFKC", text or "")
    pattern = re.compile(
        r"(?<![A-Za-z0-9.])"
        r"([+-]?\d+(?:\.\d+)?(?:\s*(?:~|～|—|－|-)\s*[+-]?\d+(?:\.\d+)?)?)"
        r"\s*(℃|°C|kPa|MPa|Pa|VDC|VAC|kV|mV|V|mA|kA|A|Hz|kHz|MHz|GHz|Ω|pF|μs|us|ms|s|min|h|小时|分钟|米|m|mm|cm|英尺|ft|g|G|%|次|个|循环|dBμV|dBµV|dB|W|kW|L/min|ml|mL)?",
        re.I,
    )
    out = []
    for m in pattern.finditer(raw):
        number, unit = m.group(1), m.group(2) or ""
        # Ignore headings, rule ordinals, years, and bare integers unless unusually distinctive.
        if not unit and (number.isdigit() and (int(number) <= 30 or 1900 <= int(number) <= 2100)):
            continue
        token = norm(number + unit)
        out.append(token)
    return sorted(set(out))


lines = standard.splitlines()
chapter_starts = {}
for chapter in range(1, 27):
    candidates = []
    patterns = [
        re.compile(rf"^#\s+{chapter}\.0(?:\s|$)"),
        re.compile(rf"^#\s+第\s*{chapter}\s*章(?:\s|$)"),
        re.compile(rf"^第\s*{chapter}\s*部分(?:\s|$)"),
    ]
    for idx, line in enumerate(lines):
        if any(pattern.search(line) for pattern in patterns):
            candidates.append(idx)
    # For chapters 15-23 and 25, converted Markdown often preserves only the
    # page header "第 N 部分"; selecting the last candidate still finds it.
    if candidates:
        chapter_starts[chapter] = max(candidates)

chapter_text = {}
for chapter, start in chapter_starts.items():
    later = [pos for ch, pos in chapter_starts.items() if ch > chapter and pos > start]
    end = min(later) if later else len(lines)
    chapter_text[chapter] = "\n".join(lines[start:end])

# QTP rows grouped by chapter headings.
qtp_chapters = defaultdict(list)
qtp_general = defaultdict(list)
for sheet in workbook:
    current_chapter = None
    current_group = "开头通用项"
    for row_idx, row in enumerate(sheet["values"], 1):
        cells = [str(v).strip() if v is not None else "" for v in row]
        joined = " | ".join(v for v in cells if v)
        match = re.search(r"试验实施\s*[-－—]?\s*(\d+)\s*章", joined)
        if match:
            current_chapter = int(match.group(1))
        if current_chapter:
            qtp_chapters[current_chapter].append((sheet["name"], row_idx, joined))
        else:
            if cells[0] and not cells[0].isdigit() and len([x for x in cells if x]) == 1:
                current_group = cells[0]
            qtp_general[current_group].append((sheet["name"], row_idx, joined))

rules_by_chapter = defaultdict(list)
for rule in rules:
    m = re.search(r"QTP-DO160G-(\d{2})-", rule["code"])
    if m:
        rules_by_chapter[int(m.group(1))].append(rule)

report = []
report.append("# 自动候选差异（须人工复核）")
report.append(f"\n标准章节定位：{sorted(chapter_starts)}")
for chapter in range(4, 27):
    if chapter not in rules_by_chapter:
        continue
    rule_text = "\n".join(r["block"] for r in rules_by_chapter[chapter])
    qtp_text = "\n".join(x[2] for x in qtp_chapters.get(chapter, []))
    std_text = chapter_text.get(chapter, "")
    qtp_nums = numeric_tokens(qtp_text)
    rule_nums = numeric_tokens(rule_text)
    std_nums = numeric_tokens(std_text)
    qtp_missing_rule = [x for x in qtp_nums if x not in norm(rule_text)]
    rule_missing_std = [x for x in rule_nums if x not in norm(std_text)]
    report.append(f"\n## 第{chapter}章")
    report.append(f"- QTP行数：{len(qtp_chapters.get(chapter, []))}；规则数：{len(rules_by_chapter[chapter])}；标准字符数：{len(std_text)}")
    report.append(f"- QTP数值未原样出现在规则：{qtp_missing_rule}")
    report.append(f"- 规则数值未原样出现在标准：{rule_missing_std}")
    report.append("- 规则标题：" + "；".join(r["title"] for r in rules_by_chapter[chapter]))

(AUDIT / "candidate_diffs.md").write_text("\n".join(report), encoding="utf-8")
(AUDIT / "standard_chapters.json").write_text(
    json.dumps({str(k): v for k, v in chapter_text.items()}, ensure_ascii=False), encoding="utf-8"
)
(AUDIT / "qtp_chapters.json").write_text(
    json.dumps({str(k): v for k, v in qtp_chapters.items()}, ensure_ascii=False, indent=2), encoding="utf-8"
)
print(json.dumps({
    "chapter_starts": chapter_starts,
    "qtp_chapters": {k: len(v) for k, v in qtp_chapters.items()},
    "rules_by_chapter": {k: len(v) for k, v in rules_by_chapter.items()},
}, ensure_ascii=False, indent=2))
