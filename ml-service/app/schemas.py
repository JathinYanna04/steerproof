from typing import Literal, Optional, Any
from pydantic import BaseModel, Field

class BBox(BaseModel):
    l: float
    t: float
    r: float
    b: float

class Passage(BaseModel):
    id: str
    section: Optional[str] = None
    kind: str
    page: int
    bbox: Optional[dict[str, float]] = None
    page_width: Optional[float] = None
    page_height: Optional[float] = None
    text: str
    ord: int

class PaperMeta(BaseModel):
    title: Optional[str] = None
    authors: Optional[str] = None
    year: Optional[int] = None
    journal: Optional[str] = None
    doi: Optional[str] = None

class ParseRequest(BaseModel):
    paperId: str
    pdfPath: str

class ParseResponse(BaseModel):
    meta: PaperMeta
    pageCount: int
    passages: list[Passage]
    pageImages: list[str]

class NumberRef(BaseModel):
    value: str
    unit: str
    context: str

class ExtractedClaim(BaseModel):
    passageId: str
    quote: str
    scientificClaim: str
    consumerDraft: str
    numbers: list[NumberRef] = []
    studyDesign: str
    population: str
    findingClass: Literal["PRIMARY", "SECONDARY", "EXPLORATORY", "BACKGROUND"]
    limitations: list[str] = []

class ExtractionResult(BaseModel):
    claims: list[ExtractedClaim]

class ExtractRequest(BaseModel):
    paperId: str
    meta: Optional[PaperMeta] = None
    passages: list[Passage]
    theme: Optional[str] = None

class ExtractResponse(BaseModel):
    claims: list[ExtractedClaim]
    llmCalls: int
    cacheHits: int

class VerifyItem(BaseModel):
    index: int
    entailment: Literal["SUPPORTED", "OVERCLAIM", "UNSUPPORTED"]
    entailmentReason: str
    risk: Literal["GREEN", "AMBER", "RED"]
    riskReason: str
    populationFit: Literal["HIGH", "MEDIUM", "LOW"]
    populationReason: str

class VerifyResult(BaseModel):
    items: list[VerifyItem]

class VerifyClaimInput(BaseModel):
    passageId: str
    quote: str
    scientificClaim: str
    consumerDraft: Optional[str] = None
    numbers: list[NumberRef] = []
    studyDesign: Optional[str] = None
    population: Optional[str] = None
    findingClass: Literal["PRIMARY", "SECONDARY", "EXPLORATORY", "BACKGROUND"]
    limitations: list[str] = []

class VerifyRequest(BaseModel):
    paperId: str
    claims: list[VerifyClaimInput]
    passages: list[Passage]

class ClaimVerifyDetail(BaseModel):
    index: int
    passageId: str
    quoteScore: int
    numbersOk: bool
    missingNumbers: list[str] = []
    entailment: Literal["SUPPORTED", "OVERCLAIM", "UNSUPPORTED"]
    risk: Literal["GREEN", "AMBER", "RED"]
    riskReason: str
    populationFit: Literal["HIGH", "MEDIUM", "LOW"]
    populationReason: str

class VerifyResponse(BaseModel):
    results: list[ClaimVerifyDetail]
    llmCalls: int
    cacheHits: int
