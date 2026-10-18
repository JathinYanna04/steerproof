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

def split_large_passages_and_merge(raw_passages: list[dict], pdf_full_path: str, paper_id: str) -> list[Passage]:
    """
    1. Splits any passage whose bbox height > 45% of page height (unless single table).
    2. Merges only consecutive paragraphs on the same page and section when EACH is under 200 chars.
    3. Never merges across pages.
    """
    doc = pymupdf.open(pdf_full_path)
    split_list: list[dict] = []

    for p in raw_passages:
        page_h = p["page_height"]
        bbox = p["bbox"]
        h = bbox["b"] - bbox["t"]

        # If height > 45% of page height and not a table, split into sub-paragraphs
        if h > 0.45 * page_h and p["kind"] != "table":
            page = doc[p["page"] - 1]
            text = p["text"]

            # Split by double newlines or sentences into ~400 char segments
            paras = [para.strip() for para in text.split("\n\n") if len(para.strip()) > 10]
            if len(paras) <= 1:
                sentences = [s.strip() + "." for s in text.split(". ") if len(s.strip()) > 5]
                paras = []
                cur_para = []
                cur_len = 0
                for s in sentences:
                    if cur_len + len(s) > 350 and cur_para:
                        paras.append(" ".join(cur_para))
                        cur_para = [s]
                        cur_len = len(s)
                    else:
                        cur_para.append(s)
                        cur_len += len(s)
                if cur_para:
                    paras.append(" ".join(cur_para))

            for idx, para in enumerate(paras):
                first_words = " ".join(para.split()[:5])
                last_words = " ".join(para.split()[-5:])
                r1 = page.search_for(first_words)
                r2 = page.search_for(last_words)
                if r1 and r2:
                    sub_l = min(min(r.x0 for r in r1), min(r.x0 for r in r2))
                    sub_r = max(max(r.x1 for r in r1), max(r.x1 for r in r2))
                    sub_t = min(r.y0 for r in r1)
                    sub_b = max(r.y1 for r in r2)
                else:
                    frac0 = idx / len(paras)
                    frac1 = (idx + 1) / len(paras)
                    sub_l = bbox["l"]
                    sub_r = bbox["r"]
                    sub_t = bbox["t"] + frac0 * h
                    sub_b = bbox["t"] + frac1 * h

                split_list.append({
                    "section": p["section"],
                    "kind": p["kind"],
                    "page": p["page"],
                    "bbox": {"l": float(sub_l), "t": float(sub_t), "r": float(sub_r), "b": float(sub_b)},
                    "page_width": p["page_width"],
                    "page_height": p["page_height"],
                    "text": para
                })
        else:
            split_list.append(p)

    # Merging step:
    # "only merge consecutive paragraphs on the same page and section when EACH is under 200 chars, and never merge across pages"
    merged_items: list[dict] = []
    i = 0
    while i < len(split_list):
        curr = split_list[i]
        if i + 1 < len(split_list):
            nxt = split_list[i + 1]
            if (curr["page"] == nxt["page"]
                and curr["section"] == nxt["section"]
                and curr["kind"] == "paragraph" and nxt["kind"] == "paragraph"
                and len(curr["text"].strip()) < 200 and len(nxt["text"].strip()) < 200):

                merged_bbox = {
                    "l": min(curr["bbox"]["l"], nxt["bbox"]["l"]),
                    "t": min(curr["bbox"]["t"], nxt["bbox"]["t"]),
                    "r": max(curr["bbox"]["r"], nxt["bbox"]["r"]),
                    "b": max(curr["bbox"]["b"], nxt["bbox"]["b"]),
                }
                merged_items.append({
                    "section": curr["section"],
                    "kind": "paragraph",
                    "page": curr["page"],
                    "bbox": merged_bbox,
                    "page_width": curr["page_width"],
                    "page_height": curr["page_height"],
                    "text": curr["text"].strip() + "\n\n" + nxt["text"].strip()
                })
                i += 2
                continue

        merged_items.append(curr)
        i += 1

    # Build final Passage objects with clean ord and ID
    paper_num = paper_id[2:] if paper_id.startswith("P-") else paper_id
    passages: list[Passage] = []
    for ord_idx, item in enumerate(merged_items, start=1):
        pid = f"E-{paper_num}-{ord_idx:04d}"
        passages.append(Passage(
            id=pid,
            section=item["section"],
            kind=item["kind"],
            page=item["page"],
            bbox=item["bbox"],
            page_width=item["page_width"],
            page_height=item["page_height"],
            text=item["text"].strip(),
            ord=ord_idx
        ))

    return passages

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

    raw_passages: list[dict] = []

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

            raw_bbox = prov.bbox
            if hasattr(raw_bbox, "to_top_left_origin"):
                bbox = raw_bbox.to_top_left_origin(page_height=page.size.height)
                bbox_dict = {"l": float(bbox.l), "t": float(bbox.t), "r": float(bbox.r), "b": float(bbox.b)}
            else:
                bbox_dict = {"l": float(raw_bbox.l), "t": float(raw_bbox.t), "r": float(raw_bbox.r), "b": float(raw_bbox.b)}

            raw_passages.append({
                "section": section,
                "kind": normalize_label(label),
                "page": page_no,
                "bbox": bbox_dict,
                "page_width": float(page.size.width),
                "page_height": float(page.size.height),
                "text": text.strip()
            })

    except Exception:
        raw_passages = []

    # Fallback to PyMuPDF if Docling failed or returned fewer than 10 passages
    if len(raw_passages) < 10:
        raw_passages = []
        doc = pymupdf.open(pdf_full_path)
        for page_idx, page in enumerate(doc):
            page_no = page_idx + 1
            blocks = page.get_text("blocks")
            for b in blocks:
                text = b[4].strip()
                if len(text) < 20:
                    continue
                raw_passages.append({
                    "section": None,
                    "kind": "paragraph",
                    "page": page_no,
                    "bbox": {"l": float(b[0]), "t": float(b[1]), "r": float(b[2]), "b": float(b[3])},
                    "page_width": float(page.rect.width),
                    "page_height": float(page.rect.height),
                    "text": text
                })

    # Apply strict splitting and merging
    passages = split_large_passages_and_merge(raw_passages, pdf_full_path, paper_id)

    # Save to disk cache
    try:
        cache_path.write_text(json.dumps([p.model_dump() for p in passages], indent=2), encoding="utf-8")
    except Exception:
        pass

    return passages
