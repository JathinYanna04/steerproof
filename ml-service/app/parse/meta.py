"""Paper metadata extraction.

Every field comes from a real source, in this order:
  1. The PDF itself (first-page text and PDF metadata).
  2. Crossref (by DOI, or by exact title + first author), accepted only when the
     Crossref title fuzzy-matches the PDF title and the first author's surname matches.
  3. PubMed esummary (by DOI) for the NLM journal abbreviation, with the same checks.
Fields that no source confirms stay None. There are no hardcoded defaults.
"""
from __future__ import annotations

import hashlib
import json
import logging
import re
from pathlib import Path
from typing import Any, Callable, Optional

import httpx
from rapidfuzz import fuzz

from app.schemas import PaperMeta

logger = logging.getLogger(__name__)

DOI_RE = re.compile(r"\b(10\.\d{4,9}/[^\s|;,]+)", re.IGNORECASE)
YEAR_RE = re.compile(r"\b(19[5-9]\d|20[0-4]\d)\b")
JOURNAL_LINE_RE = re.compile(r"Journal:\s*([^|(\n]+?)\s*(?:\((\d{4})\))?\s*(?:\||$)", re.IGNORECASE | re.MULTILINE)
AUTHORS_LINE_RE = re.compile(r"^\s*Authors?:\s*(.+)$", re.IGNORECASE | re.MULTILINE)
SECTION_HEADINGS = {"abstract", "introduction", "background", "methods", "materials and methods",
                    "results", "discussion", "conclusion", "conclusions", "references", "aim"}

HttpGet = Callable[[str, dict], Optional[dict]]


def _clean(s: str) -> str:
    s = re.sub(r"<[^>]+>", "", s)  # Crossref titles can carry JATS tags like <scp>
    return re.sub(r"\s+", " ", s).strip()


def _first_author_surname(authors: Optional[str]) -> Optional[str]:
    if not authors:
        return None
    first = authors.split(",")[0].strip()
    parts = first.split()
    return parts[-1] if parts else None


def parse_pdf_text(first_pages_text: str, title_passage: Optional[str] = None,
                   pdf_metadata: Optional[dict] = None) -> tuple[dict, dict]:
    """Extract what the PDF itself states. Returns (fields, sources)."""
    fields: dict[str, Any] = {"title": None, "authors": None, "year": None, "journal": None, "doi": None}
    sources: dict[str, str] = {}
    text = first_pages_text or ""
    md = pdf_metadata or {}

    if title_passage and _clean(title_passage).lower() not in SECTION_HEADINGS:
        fields["title"], sources["title"] = _clean(title_passage), "pdf:title-passage"
    elif md.get("title"):
        fields["title"], sources["title"] = _clean(md["title"]), "pdf:metadata"

    m = AUTHORS_LINE_RE.search(text)
    if m:
        # Author lists can wrap over several lines; continue until a line that starts a new field.
        start = m.start(1)
        tail = text[start:]
        stop = re.search(r"\n\s*(Journal:|Abstract|DOI:|Keywords|Introduction)", tail, re.IGNORECASE)
        raw = tail[: stop.start()] if stop else tail.split("\n")[0]
        authors = _clean(raw).rstrip(",")
        if authors:
            fields["authors"], sources["authors"] = authors, "pdf:authors-line"
    elif md.get("author"):
        fields["authors"], sources["authors"] = _clean(md["author"]), "pdf:metadata"

    jm = JOURNAL_LINE_RE.search(text)
    if jm:
        fields["journal"], sources["journal"] = _clean(jm.group(1)), "pdf:journal-line"
        if jm.group(2):
            fields["year"], sources["year"] = int(jm.group(2)), "pdf:journal-line"

    dm = DOI_RE.search(text)
    if dm:
        fields["doi"], sources["doi"] = dm.group(1).rstrip(".)"), "pdf:text"

    return fields, sources


def _default_http_get(url: str, params: dict) -> Optional[dict]:
    try:
        r = httpx.get(url, params=params, timeout=20.0,
                      headers={"User-Agent": "SteerProof/1.0 (metadata lookup)"})
        if r.status_code != 200:
            logger.warning("meta lookup %s -> HTTP %s", url, r.status_code)
            return None
        return r.json()
    except Exception as e:  # network failures leave fields None, they never invent values
        logger.warning("meta lookup %s failed: %s", url, e)
        return None


class _CachedGet:
    """Permanent on-disk cache for bibliographic lookups (reruns cost zero requests)."""

    def __init__(self, cache_dir: Optional[Path], http_get: HttpGet):
        self.cache_dir = cache_dir
        self.http_get = http_get

    def __call__(self, url: str, params: dict) -> Optional[dict]:
        key = hashlib.sha256(json.dumps([url, params], sort_keys=True).encode()).hexdigest()
        path = self.cache_dir / f"{key}.json" if self.cache_dir else None
        if path and path.exists():
            return json.loads(path.read_text())
        data = self.http_get(url, params)
        if data is not None and path:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(data))
        return data


def _crossref_match(item: dict, title: Optional[str], surname: Optional[str]) -> bool:
    cr_title = _clean((item.get("title") or [""])[0])
    if not cr_title:
        return False
    if title:
        # The PDF title may be truncated, so the PDF title must be contained in the Crossref title.
        if fuzz.partial_ratio(title.lower(), cr_title.lower()) < 95:
            return False
    if surname:
        families = [(a.get("family") or "").lower() for a in item.get("author", [])]
        if not families or families[0] != surname.lower():
            return False
    return bool(title or surname)


def lookup_crossref(fields: dict, get: HttpGet) -> tuple[dict, dict]:
    found: dict[str, Any] = {}
    sources: dict[str, str] = {}
    surname = _first_author_surname(fields.get("authors"))
    item = None
    if fields.get("doi"):
        data = get(f"https://api.crossref.org/works/{fields['doi']}", {})
        cand = (data or {}).get("message")
        if cand and _crossref_match(cand, fields.get("title"), surname):
            item = cand
    elif fields.get("title") and surname:
        data = get("https://api.crossref.org/works",
                   {"query.bibliographic": fields["title"], "query.author": surname, "rows": 5})
        for cand in (data or {}).get("message", {}).get("items", []):
            cr_title = _clean((cand.get("title") or [""])[0])
            if fuzz.token_sort_ratio(fields["title"].lower(), cr_title.lower()) >= 95 \
                    and _crossref_match(cand, fields["title"], surname):
                item = cand
                break
    if not item:
        return found, sources

    cr_title = _clean((item.get("title") or [""])[0])
    found["doi"], sources["doi"] = item.get("DOI"), "crossref"
    parts = (item.get("issued") or {}).get("date-parts") or [[None]]
    if parts[0] and parts[0][0]:
        found["year"], sources["year"] = int(parts[0][0]), "crossref:issued"
    if fields.get("title") and len(cr_title) > len(fields["title"]) + 3:
        found["title"], sources["title"] = cr_title, "crossref (pdf title truncated)"
    container = (item.get("container-title") or [None])[0]
    if container:
        found["journal_full"] = _clean(container)
    return found, sources


def lookup_pubmed_abbrev(doi: str, title: Optional[str], get: HttpGet) -> Optional[str]:
    search = get("https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi",
                 {"db": "pubmed", "retmode": "json", "term": f"{doi}[doi]"})
    ids = ((search or {}).get("esearchresult") or {}).get("idlist") or []
    if len(ids) != 1:
        return None
    summary = get("https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esummary.fcgi",
                  {"db": "pubmed", "retmode": "json", "id": ids[0]})
    rec = ((summary or {}).get("result") or {}).get(ids[0]) or {}
    if title and fuzz.partial_ratio(title.lower(), _clean(rec.get("title", "")).lower()) < 95:
        return None
    return rec.get("source") or None


def extract_meta_with_sources(passages: list, pdf_path: Optional[str] = None,
                              cache_dir: Optional[Path] = None, lookup: bool = True,
                              http_get: Optional[HttpGet] = None) -> tuple[PaperMeta, dict]:
    title_passage = None
    for p in passages[:5]:
        if getattr(p, "kind", None) == "title" and getattr(p, "page", 1) == 1:
            title_passage = p.text
            break

    text, pdf_md = "", {}
    if pdf_path and Path(pdf_path).exists():
        import pymupdf
        with pymupdf.open(pdf_path) as doc:
            pdf_md = doc.metadata or {}
            text = "\n".join(doc[i].get_text() for i in range(min(2, doc.page_count)))
    if not text:
        text = "\n".join(p.text for p in passages if getattr(p, "page", 1) <= 2)

    fields, sources = parse_pdf_text(text, title_passage, pdf_md)

    if lookup:
        get = _CachedGet(cache_dir, http_get or _default_http_get)
        found, found_src = lookup_crossref(fields, get)
        for k in ("doi", "year"):
            if fields.get(k) is None and found.get(k) is not None:
                fields[k], sources[k] = found[k], found_src[k]
        if found.get("title"):
            fields["title"], sources["title"] = found["title"], found_src["title"]
        if fields.get("journal") is None and fields.get("doi"):
            abbrev = lookup_pubmed_abbrev(fields["doi"], fields.get("title"), get)
            if abbrev:
                fields["journal"], sources["journal"] = abbrev, "pubmed:esummary"
            elif found.get("journal_full"):
                fields["journal"], sources["journal"] = found["journal_full"], "crossref:container-title"

    return PaperMeta(**fields), sources


def extract_meta(passages: list, llm_client=None, pdf_path: Optional[str] = None,
                 cache_dir: Optional[Path] = None) -> PaperMeta:
    meta, sources = extract_meta_with_sources(passages, pdf_path=pdf_path, cache_dir=cache_dir)
    logger.info("paper meta extracted: %s (sources: %s)", meta.model_dump(), sources)
    return meta
