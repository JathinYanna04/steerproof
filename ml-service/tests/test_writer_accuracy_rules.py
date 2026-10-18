"""WS1 accuracy rules: citations built in code, illustrations state no facts,
qualifiers on exploratory claims, stat eligibility, quote motion, cache scoping."""
from app.schemas import (
    ApprovedClaimInput, IllustrationCheckRequest, IllustrationItem, IllustrationVerdict,
    IllustrationVerdictList, PaperMeta, StaticContent, WriteRequest,
)
from app.verify.illustration_check import check_illustrations
from app.write.copy_rules import is_factual, stat_value
from app.write.validator import validate_writer_result
from app.write.writer import writer_cache_key_extra
from tests.test_writer_validation import make_valid_writer_result

FACT_SENTENCE = "Your biology is actively signaling for fuel after calorie deficits."


def exploratory_claim(cid="C-0002-002", text="One year after stopping, nearly half still maintained at least 5% weight loss."):
    return ApprovedClaimInput(claimId=cid, scientificClaim=text, consumerDraft=text, quote=text, findingClass="EXPLORATORY")


def test_static_schema_has_no_footnote_or_citation():
    assert "footnote" not in StaticContent.model_fields
    assert "citation" not in StaticContent.model_fields


def test_valid_fixture_passes_with_claims():
    ok, errors = validate_writer_result(make_valid_writer_result(), [])
    assert ok, errors


def test_citation_text_in_copy_rejected():
    res = make_valid_writer_result()
    res.carousel[4].headline = "Evidence from *Hall & Kahan, 2018*"
    ok, errors = validate_writer_result(res)
    assert not ok
    assert any("citation text" in e for e in errors)


def test_illustration_stating_fact_flagged_by_validator():
    res = make_valid_writer_result()
    res.carousel[2].body = FACT_SENTENCE
    ok, errors = validate_writer_result(res)
    assert not ok
    assert any("illustration" in e and "fact" in e for e in errors)


class FakeLLM:
    def __init__(self, verdicts, hit=False):
        self.verdicts = verdicts
        self.hit = hit
        self.calls = []

    def structured(self, prompt, schema, temperature=0.0, model=None, cache_key_extra=""):
        self.calls.append({"prompt": prompt, "temperature": temperature, "key": cache_key_extra})
        return IllustrationVerdictList(verdicts=self.verdicts), self.hit


def test_illustration_check_batched_temperature_zero_and_flags_fact():
    llm = FakeLLM([
        IllustrationVerdict(id="slide:3", statesFact=True, reason="Claims biology signals for fuel after deficits."),
        IllustrationVerdict(id="scene:3", statesFact=False, reason="Describes a routine."),
    ])
    req = IllustrationCheckRequest(paperId="P-0001", items=[
        IllustrationItem(id="slide:3", text=FACT_SENTENCE),
        IllustrationItem(id="scene:3", text="The 4pm chai break"),
    ])
    verdicts, calls, hits = check_illustrations(req, llm)
    assert len(llm.calls) == 1, "one batched call for all items"
    assert llm.calls[0]["temperature"] == 0.0
    assert "P-0001" in llm.calls[0]["key"] and "illustration-check" in llm.calls[0]["key"]
    assert {v.id: v.statesFact for v in verdicts} == {"slide:3": True, "scene:3": False}
    assert (calls, hits) == (1, 0)


def test_illustration_check_fails_closed_on_missing_verdict():
    llm = FakeLLM([])
    req = IllustrationCheckRequest(paperId="P-0001", items=[IllustrationItem(id="slide:3", text=FACT_SENTENCE)])
    verdicts, _, _ = check_illustrations(req, llm)
    assert verdicts[0].statesFact is True


def test_missing_qualifier_on_exploratory_reel_scene():
    res = make_valid_writer_result()
    sc = res.reel[3]
    sc.claimIds = ["C-0002-002"]
    sc.onScreen = "Nearly *half* maintained weight loss."
    sc.voiceover = "Nearly half the participants still maintained at least 5% weight loss one year later."
    ok, errors = validate_writer_result(res, [exploratory_claim()])
    assert not ok
    assert any("Scene 4" in e and "onScreen" in e for e in errors)
    assert any("Scene 4" in e and "voiceover" in e for e in errors)


def test_non_factual_heading_may_rely_on_voiceover_qualifier():
    res = make_valid_writer_result()
    sc = res.reel[3]
    sc.claimIds = ["C-0002-002"]
    sc.onScreen = "What the *trial* observed"
    sc.voiceover = "In this trial, nearly half the participants still kept at least 5% off."
    ok, errors = validate_writer_result(res, [exploratory_claim()])
    assert ok, errors


def test_qualifier_required_in_caption_for_exploratory():
    res = make_valid_writer_result()
    res.caption.paragraphs[1].claimIds = ["C-0002-002"]
    res.caption.paragraphs[1].text = "Nearly half kept at least 5% of the weight off a year later."
    ok, errors = validate_writer_result(res, [exploratory_claim()])
    assert not ok
    assert any("Caption para 2" in e for e in errors)


def test_quote_motion_only_on_claim_with_claim_copy():
    res = make_valid_writer_result()
    res.reel[0].motion = "quote"  # kind brand
    ok, errors = validate_writer_result(res)
    assert any("Scene 1" in e and "quote" in e for e in errors)


def test_stat_eligibility():
    assert stat_value("about 100 kcal per day") == "100 kcal"
    assert stat_value("at least 5% weight loss") == "5%"
    assert stat_value("Across 29 long-term studies") is None
    assert stat_value("29 trials, 1,000 participants, 2 years") is None
    assert is_factual("Nearly *half* maintained weight loss.")
    assert not is_factual("What the *trial* observed")


def test_cache_key_scoped_by_paper_claims_and_prompt_version():
    meta = PaperMeta()
    c = exploratory_claim()
    k1 = writer_cache_key_extra(WriteRequest(paperId="P-0001", meta=meta, claims=[c]))
    k2 = writer_cache_key_extra(WriteRequest(paperId="P-0002", meta=meta, claims=[c]))
    c2 = exploratory_claim(text="Edited copy")
    k3 = writer_cache_key_extra(WriteRequest(paperId="P-0001", meta=meta, claims=[c2]))
    assert k1 != k2 and k1 != k3
    assert k1.startswith("writer-v")


def test_qualifier_phrases_with_descriptors():
    from app.write.copy_rules import has_qualifier
    assert has_qualifier("In one clinical follow-up trial, participants regained most of their weight.")
    assert has_qualifier("In one exploratory trial, weight returned.")
    assert has_qualifier("In this extension study, blood pressure rose.")
    assert not has_qualifier("Trial data showed nearly half kept it off.")
    assert not is_factual("What one *trial* showed after stopping.")


def test_brand_phrase_detected_for_claim_blocks():
    from app.write.copy_rules import brand_phrase
    assert brand_phrase("It's *biology*, not willpower.") == "willpower"
    assert brand_phrase("Across 29 long-term studies, weight often returned.") is None


def test_brand_phrase_word_boundaries():
    from app.write.copy_rules import brand_phrase
    assert brand_phrase("It isn't about willpower.") == "willpower"
    assert brand_phrase("At Steer, we plan for the long term.") == "steer"
    assert brand_phrase("Steering wheel at dawn") is None
