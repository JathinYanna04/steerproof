import pytest
from unittest.mock import MagicMock
from app.extract.extractor import extract_claims
from app.schemas import Passage, ExtractionResult, ExtractedClaim, PaperMeta

def test_drop_hallucinated_passage_id():
    valid_passage = Passage(
        id="E-0001-0001",
        section="Results",
        kind="paragraph",
        page=1,
        bbox={"l": 0, "t": 0, "r": 100, "b": 100},
        page_width=612,
        page_height=792,
        text="A sustained 10% weight loss improves health parameters.",
        ord=1
    )
    
    mock_llm = MagicMock()
    mock_llm.structured.return_value = (
        ExtractionResult(claims=[
            ExtractedClaim(
                passageId="E-0001-0001",
                quote="A sustained 10% weight loss improves health parameters.",
                scientificClaim="A 10% weight loss improves health.",
                consumerDraft="Losing 10% weight helps health.",
                numbers=[],
                studyDesign="RCT",
                population="adults",
                findingClass="PRIMARY",
                limitations=[]
            ),
            ExtractedClaim(
                passageId="E-0001-9999", # Hallucinated passage id
                quote="Something that does not exist.",
                scientificClaim="Fabricated claim.",
                consumerDraft="Fabricated draft.",
                numbers=[],
                studyDesign="RCT",
                population="adults",
                findingClass="EXPLORATORY",
                limitations=[]
            )
        ]),
        False # not cached
    )

    claims, calls, hits = extract_claims(
        paper_id="P-0001",
        passages=[valid_passage],
        meta=PaperMeta(title="Test Paper"),
        theme=None,
        llm=mock_llm
    )

    assert len(claims) == 1
    assert claims[0].passageId == "E-0001-0001"
