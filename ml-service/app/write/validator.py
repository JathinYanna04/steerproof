import re
from typing import List, Optional, Sequence, Tuple
from app.schemas import WriterResult, ApprovedClaimInput
from app.write.copy_rules import (
    brand_phrase, has_citation_text, illustration_fact_hint, qualifier_errors, has_qualifier, stat_value,
)

PERCENT_OR_STAT_REGEX = re.compile(r'\d+(?:\.\d+)?\s*(?:%|\bpercent\b)|\b\d+\s*(?:kg|kilos|pounds|lbs)\b', re.IGNORECASE)

def _approved_text(c: ApprovedClaimInput) -> str:
    return c.approvedCopy or c.consumerDraft or c.scientificClaim


def validate_writer_result(
    result: WriterResult, claims: Optional[Sequence[ApprovedClaimInput]] = None
) -> Tuple[bool, List[str]]:
    errors: List[str] = []
    claims = claims or []
    exploratory = {c.claimId for c in claims if (c.findingClass or "").upper() == "EXPLORATORY"}
    approved_text = {c.claimId: _approved_text(c) for c in claims}

    def linked_exploratory(ids: list[str]) -> bool:
        return any(cid in exploratory for cid in ids)

    # 1. Carousel validation
    if not (5 <= len(result.carousel) <= 7):
        errors.append(f"Carousel must have between 5 and 7 slides, found {len(result.carousel)}.")

    maroon_count = 0
    for idx, slide in enumerate(result.carousel):
        s_num = slide.n or (idx + 1)
        if len(slide.headline) > 48:
            errors.append(f"Slide {s_num}: headline exceeds 48 chars ({len(slide.headline)} chars: '{slide.headline}')")
        if len(slide.body) > 160:
            errors.append(f"Slide {s_num}: body exceeds 160 chars ({len(slide.body)} chars)")
        if slide.kind == "claim" and not slide.claimIds:
            errors.append(f"Slide {s_num}: block kind is 'claim' but claimIds is empty.")
        if slide.kind == "illustration":
            if slide.claimIds:
                errors.append(f"Slide {s_num}: block kind is 'illustration' but specifies claimIds: {slide.claimIds}.")
            if PERCENT_OR_STAT_REGEX.search(slide.headline) or PERCENT_OR_STAT_REGEX.search(slide.body):
                errors.append(f"Slide {s_num}: illustration block must not contain percentages or statistics.")
        if slide.kind == "brand" and slide.claimIds:
            errors.append(f"Slide {s_num}: brand block must have empty claimIds.")
        if slide.background == "maroon":
            maroon_count += 1

    if maroon_count > 1:
        errors.append(f"Carousel must have at most 1 maroon slide, found {maroon_count}.")

    if result.carousel:
        if result.carousel[0].type != "hook":
            errors.append(f"First slide type must be 'hook', got '{result.carousel[0].type}'.")
        if result.carousel[-1].type != "closing":
            errors.append(f"Last slide type must be 'closing', got '{result.carousel[-1].type}'.")

    # 2. Static validation
    stat = result.static
    if len(stat.headline) > 48:
        errors.append(f"Static: headline exceeds 48 chars ({len(stat.headline)} chars)")
    if len(stat.support) > 110:
        errors.append(f"Static: support text exceeds 110 chars ({len(stat.support)} chars)")
    if stat.kind == "claim" and not stat.claimIds:
        errors.append("Static: block kind is 'claim' but claimIds is empty.")
    if stat.kind == "illustration" and stat.claimIds:
        errors.append(f"Static: illustration block must have empty claimIds.")

    # 3. Reel validation
    if len(result.reel) != 5:
        errors.append(f"Reel must have exactly 5 scenes, found {len(result.reel)}.")

    total_reel_words = 0
    for idx, scene in enumerate(result.reel):
        s_num = scene.n or (idx + 1)
        if len(scene.onScreen) > 40:
            errors.append(f"Scene {s_num}: onScreen text exceeds 40 chars ({len(scene.onScreen)} chars)")
        word_count = len(scene.voiceover.split())
        total_reel_words += word_count
        if word_count > 25:
            errors.append(f"Scene {s_num}: voiceover exceeds 25 words ({word_count} words)")
        if scene.kind == "claim" and not scene.claimIds:
            errors.append(f"Scene {s_num}: block kind is 'claim' but claimIds is empty.")
        if scene.kind == "illustration" and scene.claimIds:
            errors.append(f"Scene {s_num}: illustration block must have empty claimIds.")
        if scene.kind == "brand" and scene.claimIds:
            errors.append(f"Scene {s_num}: brand block must have empty claimIds.")

    if total_reel_words > 85:
        errors.append(f"Reel total voiceover word count must not exceed 85 words (found {total_reel_words} words). Keep voiceover concise (≈ 15-17 words per scene).")

    # 4. Caption validation
    total_caption_chars = sum(len(p.text) for p in result.caption.paragraphs)
    if total_caption_chars > 1800:
        errors.append(f"Caption exceeds 1,800 chars (total {total_caption_chars} chars)")
    for idx, p in enumerate(result.caption.paragraphs):
        if p.kind == "claim" and not p.claimIds:
            errors.append(f"Caption para {idx+1}: block kind is 'claim' but claimIds is empty.")
        if p.kind == "illustration" and p.claimIds:
            errors.append(f"Caption para {idx+1}: illustration block must have empty claimIds.")

    if not (8 <= len(result.caption.hashtags) <= 12):
        errors.append(f"Caption must have between 8 and 12 hashtags, found {len(result.caption.hashtags)}.")

    errors.extend(_content_errors(result, linked_exploratory, approved_text))
    return len(errors) == 0, errors


def _content_errors(result: WriterResult, linked_exploratory, approved_text: dict) -> List[str]:
    errors: List[str] = []

    # Citation text is built in code; the writer must never produce it.
    texts = [(f"Slide {s.n}", s.headline + " " + s.body) for s in result.carousel]
    texts.append(("Static", result.static.headline + " " + result.static.support))
    texts += [(f"Scene {sc.n}", sc.onScreen + " " + sc.voiceover) for sc in result.reel]
    texts += [(f"Caption para {i + 1}", p.text) for i, p in enumerate(result.caption.paragraphs)]
    for where, text in texts:
        if has_citation_text(text):
            errors.append(f"{where}: contains citation text (author names, 'et al.', years or DOI). "
                          f"Remove it; citations are added automatically.")

    # Illustrations describe everyday moments only.
    illos = [(f"Slide {s.n}", s.headline + " " + s.body) for s in result.carousel if s.kind == "illustration"]
    if result.static.kind == "illustration":
        illos.append(("Static", result.static.headline + " " + result.static.support))
    illos += [(f"Scene {sc.n}", sc.onScreen + " " + sc.voiceover) for sc in result.reel if sc.kind == "illustration"]
    illos += [(f"Caption para {i + 1}", p.text) for i, p in enumerate(result.caption.paragraphs) if p.kind == "illustration"]
    for where, text in illos:
        hint = illustration_fact_hint(text)
        if hint:
            errors.append(f"{where}: illustration uses '{hint}', which reads as a biological/medical/causal fact. "
                          f"Describe only an everyday moment, feeling or routine.")

    # Brand lines never inside a claim block.
    claim_blocks = [(f"Slide {s.n}", s.headline + " " + s.body) for s in result.carousel if s.kind == "claim"]
    if result.static.kind == "claim":
        claim_blocks.append(("Static", result.static.headline + " " + result.static.support))
    claim_blocks += [(f"Scene {sc.n}", sc.onScreen + " " + sc.voiceover) for sc in result.reel if sc.kind == "claim"]
    claim_blocks += [(f"Caption para {i + 1}", p.text) for i, p in enumerate(result.caption.paragraphs) if p.kind == "claim"]
    for where, text in claim_blocks:
        hit = brand_phrase(text)
        if hit:
            errors.append(f"{where}: kind 'claim' contains the brand line '{hit}'. Brand lines are kind 'brand' "
                          f"and must be their own block.")

    # Qualifiers on every block linked to an EXPLORATORY claim, in every format.
    for s in result.carousel:
        if linked_exploratory(s.claimIds):
            errors += qualifier_errors(s.headline, s.body, f"Slide {s.n}", "headline", "body")
    st = result.static
    if linked_exploratory(st.claimIds):
        errors += qualifier_errors(st.headline, st.support, "Static", "headline", "support")
    for sc in result.reel:
        if linked_exploratory(sc.claimIds):
            errors += qualifier_errors(sc.onScreen, sc.voiceover, f"Scene {sc.n}", "onScreen", "voiceover")
    for i, p in enumerate(result.caption.paragraphs):
        if linked_exploratory(p.claimIds) and not has_qualifier(p.text):
            errors.append(f"Caption para {i + 1}: linked to an EXPLORATORY claim and lacks a qualifier.")

    # Reel motion rules.
    for sc in result.reel:
        if sc.motion == "quote":
            if sc.kind != "claim":
                errors.append(f"Scene {sc.n}: motion 'quote' is only allowed on kind 'claim'; use 'title'.")
            elif not any(sc.onScreen.strip() == approved_text.get(cid, "").strip() for cid in sc.claimIds):
                errors.append(f"Scene {sc.n}: motion 'quote' must show the claim's approvedText exactly; use 'title' instead.")
        if sc.motion == "stat" and not stat_value(sc.onScreen + " " + sc.voiceover):
            errors.append(f"Scene {sc.n}: motion 'stat' needs an outcome value (%, kg, kcal...); counts of "
                          f"studies/people/years are not stats. Use 'title'.")
    return errors
