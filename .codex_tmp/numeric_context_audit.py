import json
import re
import unicodedata
from collections import defaultdict
from decimal import Decimal, InvalidOperation
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AUDIT = ROOT / ".codex_tmp/audit"
rules = json.loads((AUDIT / "rule_entries.json").read_text(encoding="utf-8"))
chapters = json.loads((AUDIT / "standard_chapters.json").read_text(encoding="utf-8"))
qtp_chapters = json.loads((AUDIT / "qtp_chapters.json").read_text(encoding="utf-8"))

UNIT_MAP = {
    "°c": "℃", "℃": "℃", "摄氏度": "℃",
    "小时": "min", "h": "min", "hr": "min",
    "分钟": "min", "min": "min",
    "秒": "s", "s": "s", "ms": "ms", "μs": "us", "us": "us",
    "米": "m", "m": "m", "cm": "cm", "mm": "mm",
    "英尺": "ft", "ft": "ft",
    "hz": "hz", "khz": "khz", "mhz": "mhz", "ghz": "ghz",
    "kpa": "kpa", "mpa": "mpa", "pa": "pa",
    "vdc": "vdc", "vac": "vac", "kv": "kv", "mv": "mv", "v": "v",
    "ma": "ma", "ka": "ka", "a/m": "a/m", "a": "a",
    "ω": "ohm", "pF": "pf", "pf": "pf",
    "%": "%", "次": "次", "个": "个", "循环": "循环",
    "g": "g", "db": "db", "dbμv": "dbuv", "dbµv": "dbuv",
    "w": "w", "kw": "kw", "l/min": "l/min", "ml": "ml",
}

TOKEN_RE = re.compile(
    r"(?<![A-Za-z0-9])"
    r"(?P<num>[+＋-－−]?\d+(?:\.\s*\d+)?(?:\s*(?:~|～|—|－|-)\s*[+＋-－−]?\d+(?:\.\s*\d+)?)?)"
    r"\s*(?P<unit>℃|°C|摄氏度|kPa|MPa|Pa|VDC|VAC|kV|mV|V|mA|kA|A/m|A|Hz|kHz|MHz|GHz|Ω|pF|μs|us|ms|秒|s|min|分钟|h|hr|小时|米|m|cm|mm|英尺|ft|g|%|次|个|循环|dBμV|dBµV|dB|kW|W|L/min|mL|ml)",
    re.I,
)


def decimal_text(value):
    value = value.replace(" ", "").replace("＋", "+").replace("－", "-").replace("−", "-")
    value = value.lstrip("+")
    try:
        return format(Decimal(value), "f").rstrip("0").rstrip(".") or "0"
    except InvalidOperation:
        return value


def canonical(num, unit):
    unit_raw = unit.lower().replace(" ", "")
    canon_unit = UNIT_MAP.get(unit_raw, UNIT_MAP.get(unit, unit_raw))
    parts = re.split(r"\s*(?:~|～|—|－|-)\s*", num.strip())
    # Keep a leading negative sign from being interpreted as a range separator.
    if num.strip().startswith(("-", "－", "−")):
        body = num.strip()[1:]
        range_parts = re.split(r"\s*(?:~|～|—|－)\s*|(?<=\d)\s*-\s*(?=[+＋-－−]?\d)", body)
        parts = ["-" + range_parts[0]] + range_parts[1:]
    values = [decimal_text(p) for p in parts if p.strip()]
    if canon_unit == "min" and unit_raw in ("h", "hr", "小时"):
        converted = []
        for v in values:
            try:
                converted.append(decimal_text(str(Decimal(v) * 60)))
            except InvalidOperation:
                converted.append(v)
        values = converted
    return (tuple(values), canon_unit)


def tokens(text):
    found = []
    for m in TOKEN_RE.finditer(unicodedata.normalize("NFKC", text or "")):
        before = (text or "")[max(0, m.start() - 12):m.start()]
        raw = m.group(0)
        if re.search(r"DO\s*[-－]?\s*$", before, re.I) and re.match(r"160\s*[gG]", raw):
            continue
        found.append((canonical(m.group("num"), m.group("unit")), raw, m.start()))
    return found


def context(text, pos, radius=85):
    start = max(0, pos - radius)
    end = min(len(text), pos + radius)
    return re.sub(r"\s+", " ", text[start:end]).strip()


by_chapter = defaultdict(list)
for rule in rules:
    m = re.search(r"QTP-DO160G-(\d{2})-", rule["code"])
    if m and int(rule["ordinal"]) >= 6:
        by_chapter[int(m.group(1))].append(rule)

lines = ["# 数值与单位候选差异（自动筛选，非最终结论）"]
for chapter in range(4, 27):
    std = chapters.get(str(chapter), "")
    std_tokens = {key for key, _, _ in tokens(std)}
    qtp = "\n".join(row[2] for row in qtp_chapters.get(str(chapter), []))
    rule_text = "\n".join(r["block"] for r in by_chapter.get(chapter, []))
    rule_tokens = {key for key, _, _ in tokens(rule_text)}
    lines.append(f"\n## 第{chapter}章")
    lines.append("### 规则有、标准章节未检出")
    count = 0
    seen = set()
    for rule in by_chapter.get(chapter, []):
        for key, raw, pos in tokens(rule["block"]):
            if key in std_tokens or key in seen:
                continue
            seen.add(key)
            count += 1
            lines.append(f"- {rule['code']} `{raw}`：{context(rule['block'], pos)}")
    if not count:
        lines.append("- 无")
    lines.append("### QTP有、规则章节未检出")
    count = 0
    seen = set()
    for key, raw, pos in tokens(qtp):
        if key in rule_tokens or key in seen:
            continue
        seen.add(key)
        count += 1
        lines.append(f"- `{raw}`：{context(qtp, pos)}")
    if not count:
        lines.append("- 无")

(AUDIT / "numeric_context_candidates.md").write_text("\n".join(lines), encoding="utf-8")
print(AUDIT / "numeric_context_candidates.md")
