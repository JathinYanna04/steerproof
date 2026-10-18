from pathlib import Path
import yaml
from app.schemas import VerifyClaimInput, Passage, VerifyResult, VerifyItem
from app.prompts.loader import load_prompt
from app.llm.client import LLMClient

RISK_RUBRIC_PATH = Path(__file__).resolve().parent.parent / "config" / "risk_rubric.yaml"

TARGET_POPULATION_PROFILE = """Steer's audience: adults in India, mostly 25–55, urban and semi-urban, many with
overweight, abdominal obesity, prediabetes, type 2 diabetes, PCOS or fatty liver.
Common traits: South Asian "thin-fat" phenotype (more visceral fat and insulin
resistance at lower BMI), carbohydrate-heavy diets (rice, roti), sedentary desk jobs,
family-centred eating. Indian or South Asian study data counts as a HIGH fit."""

def format_verify_items(claims_slice: list[tuple[int, VerifyClaimInput]], passages_by_id: dict[str, Passage]) -> str:
    lines = []
    for idx, claim in claims_slice:
        p = passages_by_id.get(claim.passageId)
        sec = p.section if p and p.section else "Unknown"
        lines.append(
            f"Item {idx}:\n"
            f"SOURCE QUOTE: \"{claim.quote}\"\n"
            f"SECTION: {sec}\n"
            f"STUDY DESIGN: {claim.studyDesign or 'Not stated'}\n"
            f"POPULATION: {claim.population or 'Not stated'}\n"
            f"SCIENTIFIC CLAIM: {claim.scientificClaim}\n"
            f"CONSUMER DRAFT: {claim.consumerDraft or 'None'}\n"
        )
    return "\n---\n".join(lines)

def run_semantic_verification(
    claims: list[VerifyClaimInput],
    passages: list[Passage],
    llm: LLMClient,
    batch_size: int = 6
) -> tuple[dict[int, VerifyItem], int, int]:
    passages_by_id = {p.id: p for p in passages}
    
    risk_rubric_raw = RISK_RUBRIC_PATH.read_text(encoding="utf-8") if RISK_RUBRIC_PATH.exists() else ""

    indexed_claims = list(enumerate(claims))
    total_calls = 0
    total_hits = 0
    results: dict[int, VerifyItem] = {}

    for i in range(0, len(indexed_claims), batch_size):
        batch = indexed_claims[i:i + batch_size]
        items_str = format_verify_items(batch, passages_by_id)
        
        prompt = load_prompt("verifier", {
            "risk_rubric": risk_rubric_raw,
            "population_profile": TARGET_POPULATION_PROFILE,
            "items": items_str
        })

        verify_res, hit = llm.structured(
            prompt=prompt,
            schema=VerifyResult,
            temperature=0.0
        )
        if hit:
            total_hits += 1
        else:
            total_calls += 1

        for item in verify_res.items:
            risk = item.risk
            if item.entailment == "UNSUPPORTED":
                risk = "RED"
            elif item.entailment == "OVERCLAIM" and risk == "GREEN":
                risk = "AMBER"
            results[item.index] = VerifyItem(
                index=item.index,
                entailment=item.entailment,
                entailmentReason=item.entailmentReason,
                risk=risk,
                riskReason=item.riskReason,
                populationFit=item.populationFit,
                populationReason=item.populationReason
            )

    return results, total_calls, total_hits
