import pytest
from app.verify.quote_check import norm, check_quote_with_relink
from app.schemas import Passage

def test_exact_quote():
    passage = Passage(
        id="E-0001-0001",
        section="Results",
        kind="paragraph",
        page=1,
        bbox={"l": 0, "t": 0, "r": 100, "b": 100},
        page_width=612,
        page_height=792,
        text="A sustained 10% weight loss produces clinically meaningful improvements in glycemic control.",
        ord=1
    )
    quote = "A sustained 10% weight loss produces clinically meaningful improvements in glycemic control."
    score, pid = check_quote_with_relink(quote, passage.id, {passage.id: passage}, {passage.ord: passage})
    assert score == 100
    assert pid == passage.id

def test_quote_whitespace_case_endash():
    passage = Passage(
        id="E-0001-0001",
        section="Results",
        kind="paragraph",
        page=1,
        bbox={"l": 0, "t": 0, "r": 100, "b": 100},
        page_width=612,
        page_height=792,
        text="Weight loss was −5.2 kg (95% CI: –6.1 to –4.3) in the intervention group.",
        ord=1
    )
    # Using different unicode dashes and extra whitespace and different casing
    quote = "weight loss was -5.2 kg (95% ci: -6.1 to -4.3)   in the intervention group."
    score, pid = check_quote_with_relink(quote, passage.id, {passage.id: passage}, {passage.ord: passage})
    assert score >= 95
    assert pid == passage.id

def test_paraphrase_fails():
    passage = Passage(
        id="E-0001-0001",
        section="Results",
        kind="paragraph",
        page=1,
        bbox={"l": 0, "t": 0, "r": 100, "b": 100},
        page_width=612,
        page_height=792,
        text="A sustained 10% weight loss produces clinically meaningful improvements in glycemic control.",
        ord=1
    )
    paraphrase = "Losing ten percent of body weight helps manage blood sugar levels effectively over time."
    score, pid = check_quote_with_relink(paraphrase, passage.id, {passage.id: passage}, {passage.ord: passage})
    assert score < 90

def test_relink_to_neighbour():
    p1 = Passage(
        id="E-0001-0001",
        section="Results",
        kind="paragraph",
        page=1,
        bbox={"l": 0, "t": 0, "r": 100, "b": 50},
        page_width=612,
        page_height=792,
        text="The study included 400 participants randomized into two arms.",
        ord=1
    )
    p2 = Passage(
        id="E-0001-0002",
        section="Results",
        kind="paragraph",
        page=1,
        bbox={"l": 0, "t": 50, "r": 100, "b": 100},
        page_width=612,
        page_height=792,
        text="Mean weight reduction at 52 weeks was 14.9% with semaglutide versus 2.4% with placebo.",
        ord=2
    )
    passages_by_id = {p1.id: p1, p2.id: p2}
    passages_by_ord = {p1.ord: p1, p2.ord: p2}

    # Quote is from p2, but mistakenly attributed to p1
    quote = "Mean weight reduction at 52 weeks was 14.9% with semaglutide versus 2.4% with placebo."
    score, best_pid = check_quote_with_relink(quote, p1.id, passages_by_id, passages_by_ord)
    assert score >= 95
    assert best_pid == p2.id
