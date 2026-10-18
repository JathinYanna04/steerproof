import pytest
from app.schemas import WriterResult, Slide, StaticContent, Scene, CaptionContent, CaptionPara
from app.write.validator import validate_writer_result

def make_valid_writer_result():
    carousel = [
        Slide(n=1, type="hook", headline="*Why* is weight regain so common?", body="Let's look at what clinical trials show.", kind="brand", claimIds=[], background="cream"),
        Slide(n=2, type="point", headline="Appetite signals *surge* after weight loss", body="For each kilo lost, daily appetite increases.", kind="claim", claimIds=["C-0001-002"], background="cream"),
        Slide(n=3, type="illustration", headline="Think of your body after a *family dinner*", body="Your hormones naturally try to restore previous energy levels.", kind="illustration", claimIds=[], background="cream"),
        Slide(n=4, type="point", headline="Long-term *support* changes the outcome", body="Ongoing medical guidance improves weight-loss maintenance.", kind="claim", claimIds=["C-0001-003"], background="cream"),
        Slide(n=5, type="source", headline="Evidence from *Hall & Kahan, 2018*", body="Published in Med Clin North Am.", kind="brand", claimIds=[], background="cream"),
        Slide(n=6, type="closing", headline="Doctor-led care for *lasting* metabolic health", body="steer · steerclub.com", kind="brand", claimIds=[], background="maroon"),
    ]

    static = StaticContent(
        headline="Why weight *regain* happens",
        support="Appetite hormones surge as calorie burn drops.",
        kind="claim",
        claimIds=["C-0001-002"],
        footnote="Source: Hall & Kahan, 2018 · Med Clin North Am"
    )

    reel = [
        Scene(n=1, onScreen="Why is weight regain so common?", voiceover="Have you ever wondered why weight regain happens?", kind="brand", claimIds=[], brollQuery="chai cup table", motion="title"),
        Scene(n=2, onScreen="Appetite hormones surge", voiceover="In clinical trials, appetite increased as weight dropped.", kind="claim", claimIds=["C-0001-002"], brollQuery="clock wall office", motion="stat"),
        Scene(n=3, onScreen="It is biology, not willpower", voiceover="Your body triggers survival adaptations to restore lost calories.", kind="illustration", claimIds=[], brollQuery="kitchen spices food", motion="list"),
        Scene(n=4, onScreen="Long-term guidance helps", voiceover="Ongoing support from clinicians improved maintenance outcomes.", kind="claim", claimIds=["C-0001-003"], brollQuery="park walking path", motion="quote"),
        Scene(n=5, onScreen="steer · steerclub.com", voiceover="Doctor-led metabolic care. steerclub.com", kind="brand", claimIds=[], brollQuery="sunrise sky morning", motion="closing"),
    ]

    caption = CaptionContent(
        paragraphs=[
            CaptionPara(text="Weight regain is biology, not a lack of discipline.", kind="illustration", claimIds=[]),
            CaptionPara(text="In long-term clinical trials, appetite increased by 100 calories per day for each kilogram lost.", kind="claim", claimIds=["C-0001-002"]),
            CaptionPara(text="Visit steerclub.com to learn more about doctor-led metabolic care.", kind="brand", claimIds=[])
        ],
        hashtags=["#steer", "#metabolichealth", "#weightcare", "#obesitymedicine", "#healthyliving", "#indiahealth", "#doctorled", "#evidencebased"]
    )

    return WriterResult(
        coreMessage="Weight regain is driven by physiological appetite increases rather than lack of willpower.",
        reasoning="This addresses the central patient anxiety around weight regain using clear clinical trial evidence.",
        carousel=carousel,
        static=static,
        reel=reel,
        caption=caption
    )

def test_valid_writer_result_passes():
    res = make_valid_writer_result()
    ok, errors = validate_writer_result(res)
    assert ok, f"Expected valid result, but got errors: {errors}"
    assert len(errors) == 0

def test_claim_block_without_claim_ids_fails():
    res = make_valid_writer_result()
    res.carousel[1].claimIds = []  # kind is 'claim'
    ok, errors = validate_writer_result(res)
    assert not ok
    assert any("block kind is 'claim' but claimIds is empty" in e for e in errors)

def test_illustration_with_percentage_fails():
    res = make_valid_writer_result()
    res.carousel[2].headline = "Patients lost 15% of body weight"  # kind is 'illustration'
    ok, errors = validate_writer_result(res)
    assert not ok
    assert any("illustration block must not contain percentages" in e for e in errors)

def test_overlength_headline_fails():
    res = make_valid_writer_result()
    res.carousel[0].headline = "This is an extremely long headline that definitely exceeds the forty-eight character limit easily"
    ok, errors = validate_writer_result(res)
    assert not ok
    assert any("headline exceeds 48 chars" in e for e in errors)
