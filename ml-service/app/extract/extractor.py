import logging
from rapidfuzz import fuzz
from app.schemas import Passage, ExtractedClaim, ExtractionResult, PaperMeta
from app.prompts.loader import load_prompt
from app.llm.client import LLMClient

logger = logging.getLogger(__name__)

EXCLUDE_SECTION_KEYWORDS = [
    "reference", "bibliography", "author contribution", "funding", 
    "conflict of interest", "competing interest", "acknowledgment", "financial disclosure"
]

def is_excluded_passage(p: Passage) -> bool:
    if p.section:
        sec_lower = p.section.lower()
        if any(k in sec_lower for k in EXCLUDE_SECTION_KEYWORDS):
            return True
    return False

def format_passages(passages: list[Passage]) -> str:
    lines = []
    for p in passages:
        sec = f", {p.section}" if p.section else ""
        lines.append(f"[{p.id}] (p.{p.page}{sec}) {p.text}")
    return "\n\n".join(lines)

def deduplicate_claims(claims: list[ExtractedClaim]) -> list[ExtractedClaim]:
    unique: list[ExtractedClaim] = []
    for c in claims:
        is_dup = False
        for u in unique:
            if c.passageId == u.passageId:
                ratio = fuzz.ratio(c.scientificClaim.lower(), u.scientificClaim.lower())
                if ratio > 85:
                    is_dup = True
                    break
        if not is_dup:
            unique.append(c)
    return unique

def extract_claims(
    paper_id: str,
    passages: list[Passage],
    meta: PaperMeta | None,
    theme: str | None,
    llm: LLMClient
) -> tuple[list[ExtractedClaim], int, int]:
    valid_pids = {p.id for p in passages}
    
    # Filter passages
    usable_passages = [p for p in passages if not is_excluded_passage(p)]
    if not usable_passages:
        usable_passages = passages

    # Check length
    total_chars = sum(len(p.text) for p in usable_passages)
    
    chunks: list[list[Passage]] = []
    if total_chars > 100_000:
        # Split into chunks of ~60k chars
        curr_chunk = []
        curr_len = 0
        for p in usable_passages:
            p_len = len(p.text)
            if curr_len + p_len > 60_000 and curr_chunk:
                chunks.append(curr_chunk)
                curr_chunk = [p]
                curr_len = p_len
            else:
                curr_chunk.append(p)
                curr_len += p_len
        if curr_chunk:
            chunks.append(curr_chunk)
    else:
        chunks = [usable_passages]

    all_claims: list[ExtractedClaim] = []
    total_calls = 0
    total_hits = 0

    title = meta.title if meta and meta.title else "Scientific Paper"
    authors = meta.authors if meta and meta.authors else "Unknown Authors"
    year = str(meta.year) if meta and meta.year else ""
    journal = meta.journal if meta and meta.journal else ""

    for chunk in chunks:
        passages_text = format_passages(chunk)
        prompt = load_prompt("extractor", {
            "title": title,
            "authors": authors,
            "year": year,
            "journal": journal,
            "theme": theme,
            "passages": passages_text
        })

        res, hit = llm.structured(
            prompt=prompt,
            schema=ExtractionResult,
            temperature=0.0
        )
        if hit:
            total_hits += 1
        else:
            total_calls += 1

        for c in res.claims:
            if c.passageId not in valid_pids:
                logger.warning(f"Dropping hallucinated passageId: {c.passageId}")
                continue
            all_claims.append(c)

    deduped = deduplicate_claims(all_claims)
    return deduped, total_calls, total_hits
