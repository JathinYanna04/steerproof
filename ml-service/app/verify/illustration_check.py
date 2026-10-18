"""ILLUSTRATION_STATES_FACT: one batched Gemini call (temperature 0) over all illustration blocks."""
import json
from typing import Tuple

from app.llm.client import LLMClient
from app.prompts.loader import load_prompt
from app.schemas import IllustrationCheckRequest, IllustrationVerdict, IllustrationVerdictList

# Bump whenever illustration_check.md changes; it is part of the LLM cache key.
ILLUSTRATION_PROMPT_VERSION = "illustration-check-v1-2026-10-18"


def check_illustrations(req: IllustrationCheckRequest, llm: LLMClient) -> Tuple[list[IllustrationVerdict], int, int]:
    """Returns (verdicts, llmCalls, cacheHits). Items the model skips are reported as unchecked facts."""
    items = [i for i in req.items if i.text and i.text.strip()]
    if not items:
        return [], 0, 0

    items_text = "\n".join(f"- id: {i.id}\n  text: {json.dumps(i.text, ensure_ascii=False)}" for i in items)
    prompt = load_prompt("illustration_check", {"items": items_text})
    payload = json.dumps([i.model_dump() for i in items], sort_keys=True, ensure_ascii=False)
    res, hit = llm.structured(
        prompt=prompt,
        schema=IllustrationVerdictList,
        temperature=0.0,
        cache_key_extra=f"{ILLUSTRATION_PROMPT_VERSION}|{req.paperId}|{payload}",
    )

    by_id = {v.id: v for v in res.verdicts}
    verdicts: list[IllustrationVerdict] = []
    for i in items:
        v = by_id.get(i.id)
        if v is None:
            # Fail closed: an item the checker did not answer is treated as a possible fact.
            v = IllustrationVerdict(id=i.id, statesFact=True, reason="Checker returned no verdict for this item.")
        verdicts.append(v)
    return verdicts, (0 if hit else 1), (1 if hit else 0)
