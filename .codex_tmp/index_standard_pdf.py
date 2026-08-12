import json
import re
from pathlib import Path
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[1]
pdf_path = ROOT / "prompts/RTCA DO-160G中文版.pdf"
out_path = ROOT / ".codex_tmp/audit/pdf_page_index.json"

reader = PdfReader(str(pdf_path))
pages = []
chapter_hits = {str(i): [] for i in range(1, 27)}
needles = [
    "75.26", "170kPa", "204℃", "10-250Hz", "500-2000Hz",
    "15cm", "30%", "360Hz", "15.2kHz", "1.8m", "1.2m",
    "18GHz", "5~15cm", "5～15cm", "不小于1m", "不超过50mm",
    "75mm", "305mm", "330Ω", "150pF",
]
needle_hits = {n: [] for n in needles}
for idx, page in enumerate(reader.pages, start=1):
    try:
        text = page.extract_text() or ""
    except Exception:
        text = ""
    compact = re.sub(r"\s+", "", text)
    pages.append({"page": idx, "chars": len(text), "start": re.sub(r"\s+", " ", text[:240])})
    for chapter in range(1, 27):
        if re.search(rf"第\s*{chapter}\s*(?:部分|章)", text):
            chapter_hits[str(chapter)].append(idx)
    for needle in needles:
        if re.sub(r"\s+", "", needle) in compact:
            needle_hits[needle].append(idx)

out = {
    "page_count": len(reader.pages),
    "chapter_hits": chapter_hits,
    "needle_hits": needle_hits,
    "pages": pages,
}
out_path.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"page_count": len(reader.pages), "chapter_hits": chapter_hits, "needle_hits": needle_hits}, ensure_ascii=False, indent=2))
