import logging
import json
import yaml
from pathlib import Path
from typing import Tuple

from app.schemas import WriteRequest, WriteResponse, WriterResult
from app.llm.client import LLMClient
from app.prompts.loader import load_prompt
from app.write.validator import validate_writer_result

logger = logging.getLogger(__name__)

def load_voice_spec() -> str:
    path = Path(__file__).resolve().parent.parent / "config" / "voice_spec.yaml"
    if path.exists():
        return path.read_text()
    return "Steer is a doctor-led metabolic health and weight-care club in India."

def format_claims(claims) -> str:
    lines = []
    for c in claims:
        copy_text = c.approvedCopy or c.consumerDraft or c.scientificClaim
        num_str = ", ".join([f"{n.value} {n.unit}".strip() for n in c.numbers]) if c.numbers else "none"
        lines.append(
            f"- claimId: {c.claimId}\n"
            f"  findingClass: {c.findingClass}\n"
            f"  approvedText: \"{copy_text}\"\n"
            f"  scientificClaim: \"{c.scientificClaim}\"\n"
            f"  quote: \"{c.quote}\"\n"
            f"  numbers: {num_str}\n"
            f"  population: \"{c.population or 'General adults'}\""
        )
    return "\n".join(lines)

def run_writer(req: WriteRequest, llm: LLMClient) -> Tuple[WriteResponse, WriterResult]:
    voice_spec = load_voice_spec()
    claims_text = format_claims(req.claims)
    citation = f"{req.meta.authors or 'Authors'} ({req.meta.year or 2018}). {req.meta.title or 'Study'}. {req.meta.journal or ''}."
    prompt = load_prompt("writer", {
        "voice_spec": voice_spec,
        "claims": claims_text,
        "citation": citation,
        "theme": req.theme or ""
    })

    total_calls = 0
    total_hits = 0

    # First attempt
    res, hit = llm.structured(
        prompt=prompt,
        schema=WriterResult,
        temperature=0.0
    )
    if hit:
        total_hits += 1
    else:
        total_calls += 1

    ok, errors = validate_writer_result(res)
    if not ok:
        logger.warning(f"Writer output failed initial validation with {len(errors)} errors: {errors}. Retrying once...")
        retry_prompt = prompt + "\n\nYOUR PREVIOUS OUTPUT FAILED THESE CHECKS. Fix only these, keep everything else:\n"
        for err in errors:
            retry_prompt += f"- {err}\n"
        
        # Second attempt
        retry_res, retry_hit = llm.structured(
            prompt=retry_prompt,
            schema=WriterResult,
            temperature=0.0
        )
        if retry_hit:
            total_hits += 1
        else:
            total_calls += 1

        res = retry_res
        ok2, errors2 = validate_writer_result(res)
        if not ok2:
            logger.warning(f"Writer retry still had errors: {errors2}")

    response = WriteResponse(
        coreMessage=res.coreMessage,
        reasoning=res.reasoning,
        carousel={"slides": [s.model_dump() for s in res.carousel]},
        static=res.static.model_dump(),
        reel={"scenes": [sc.model_dump() for sc in res.reel]},
        caption=res.caption.model_dump(),
        llmCalls=total_calls,
        cacheHits=total_hits
    )
    return response, res
