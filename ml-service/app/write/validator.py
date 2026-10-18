import re
from typing import List, Tuple
from app.schemas import WriterResult, Slide, StaticContent, Scene, CaptionContent

PERCENT_OR_STAT_REGEX = re.compile(r'\d+(?:\.\d+)?\s*(?:%|\bpercent\b)|\b\d+\s*(?:kg|kilos|pounds|lbs)\b', re.IGNORECASE)

def validate_writer_result(result: WriterResult) -> Tuple[bool, List[str]]:
    errors: List[str] = []

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
    if len(stat.footnote) > 90:
        errors.append(f"Static: footnote exceeds 90 chars ({len(stat.footnote)} chars)")
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

    return len(errors) == 0, errors
