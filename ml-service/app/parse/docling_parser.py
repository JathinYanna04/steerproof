import os
import json
from pathlib import Path
import pymupdf
from docling.document_converter import DocumentConverter

from app.schemas import Passage

def normalize_label(label: str) -> str:
    lbl = label.lower()
    if "table" in lbl:
        return "table"
    elif "caption" in lbl:
        return "caption"
    elif "list" in lbl:
        return "list"
    elif "title" in lbl or "header" in lbl:
        return "title"
    return "paragraph"

def parse_pdf(pdf_full_path: str, paper_id: str, storage_root: Path) -> list[Passage]:
    # Check cache first
    cache_path = storage_root / "parsed" / f"{paper_id}.json"
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    if cache_path.exists():
        try:
            cached_data = json.loads(cache_path.read_text(encoding="utf-8"))
            return [Passage(**p) for p in cached_data]
        except Exception:
            pass

    passages: list[Passage] = []
    ord_ = 0

    try:
        converter = DocumentConverter()
        conv_res = converter.convert(pdf_full_path)
        doc = conv_res.document

        section = None
        for item, _level in doc.iterate_items():
            label = str(getattr(item, "label", "")).lower()
            if "section_header" in label or label.endswith("title"):
                section = getattr(item, "text", None)

            text = item.export_to_markdown(doc=doc) if "table" in label else getattr(item, "text", "")
            if not text or len(text.strip()) < 20 or not getattr(item, "prov", None):
                continue

            prov = item.prov[0]
            page_no = prov.page_no
            page = doc.pages[page_no]
            
            # Convert bbox to top-left origin
            raw_bbox = prov.bbox
            if hasattr(raw_bbox, "to_top_left_origin"):
                bbox = raw_bbox.to_top_left_origin(page_height=page.size.height)
                bbox_dict = {"l": float(bbox.l), "t": float(bbox.t), "r": float(bbox.r), "b": float(bbox.b)}
            else:
                bbox_dict = {"l": float(raw_bbox.l), "t": float(raw_bbox.t), "r": float(raw_bbox.r), "b": float(raw_bbox.b)}

            ord_ += 1
            paper_num = paper_id[2:] if paper_id.startswith("P-") else paper_id
            pid = f"E-{paper_num}-{ord_:04d}"

            passages.append(Passage(
                id=pid,
                section=section,
                kind=normalize_label(label),
                page=page_no,
                bbox=bbox_dict,
                page_width=float(page.size.width),
                page_height=float(page.size.height),
                text=text.strip(),
                ord=ord_
            ))

    except Exception as e:
        passages = []

    # Fallback to PyMuPDF if Docling failed or returned fewer than 10 passages
    if len(passages) < 10:
        passages = []
        ord_ = 0
        doc = pymupdf.open(pdf_full_path)
        for page_idx, page in enumerate(doc):
            page_no = page_idx + 1
            blocks = page.get_text("blocks") # (x0, y0, x1, y1, text, block_no, block_type)
            for b in blocks:
                text = b[4].strip()
                if len(text) < 20:
                    continue
                ord_ += 1
                paper_num = paper_id[2:] if paper_id.startswith("P-") else paper_id
                pid = f"E-{paper_num}-{ord_:04d}"
                passages.append(Passage(
                    id=pid,
                    section=None,
                    kind="paragraph",
                    page=page_no,
                    bbox={"l": float(b[0]), "t": float(b[1]), "r": float(b[2]), "b": float(b[3])},
                    page_width=float(page.rect.width),
                    page_height=float(page.rect.height),
                    text=text,
                    ord=ord_
                ))

    # Save to disk cache
    try:
        cache_path.write_text(json.dumps([p.model_dump() for p in passages], indent=2), encoding="utf-8")
    except Exception:
        pass

    return passages
