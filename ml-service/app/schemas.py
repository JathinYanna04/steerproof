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

Kind = Literal["claim", "illustration", "brand"]

class Slide(BaseModel):
    n: int
    type: Literal["hook", "point", "illustration", "source", "closing"]
    headline: str
    body: str = ""
    kind: Kind
    claimIds: list[str] = []
    background: Literal["cream", "maroon"] = "cream"

class StaticContent(BaseModel):
    headline: str
    support: str
    kind: Kind
    claimIds: list[str] = []
    footnote: str

class Scene(BaseModel):
    n: int
    onScreen: str
    voiceover: str
    kind: Kind
    claimIds: list[str] = []
    brollQuery: str
    motion: Literal["title", "stat", "list", "quote", "closing"]

class CaptionPara(BaseModel):
    text: str
    kind: Kind
    claimIds: list[str] = []

class CaptionContent(BaseModel):
    paragraphs: list[CaptionPara]
    hashtags: list[str]

class WriterResult(BaseModel):
    coreMessage: str
    reasoning: str
    carousel: list[Slide]
    static: StaticContent
    reel: list[Scene]
    caption: CaptionContent

class ApprovedClaimInput(BaseModel):
    claimId: str
    approvedCopy: Optional[str] = None
    scientificClaim: str
    consumerDraft: Optional[str] = None
    quote: str
    findingClass: str
    numbers: list[NumberRef] = []
    limitations: list[str] = []
    population: Optional[str] = None

class WriteRequest(BaseModel):
    paperId: str
    meta: PaperMeta
    claims: list[ApprovedClaimInput]
    theme: Optional[str] = None
    language: Literal["en", "hinglish"] = "en"
    only: Optional[Literal["CAROUSEL", "STATIC", "REEL", "CAPTION"]] = None

class WriteResponse(BaseModel):
    coreMessage: str
    reasoning: str
    carousel: dict
    static: dict
    reel: dict
    caption: dict
    llmCalls: int
    cacheHits: int

class ReelAudioSceneInput(BaseModel):
    n: int
    voiceover: str
    onScreen: Optional[str] = ""
    motion: Optional[str] = "title"
    kind: Optional[str] = "brand"
    claimIds: list[str] = []
    brollQuery: Optional[str] = ""

class ReelAudioRequest(BaseModel):
    assetId: Optional[str] = None
    paperId: Optional[str] = None
    scenes: list[ReelAudioSceneInput]
    voice: Optional[str] = "en-IN-NeerjaNeural"
    language: Optional[str] = "en"

class ReelAudioSceneResult(BaseModel):
    n: int
    voiceover: Optional[str] = ""
    audioPath: Optional[str] = None
    mp3Path: Optional[str] = None
    durationMs: int
    wordCount: int = 0
    words: list[dict]
    timingApproximate: bool = False
    onScreen: str = ""
    motion: str = "title"
    kind: str = "brand"
    claimIds: list[str] = []
    brollQuery: str = ""

class ReelAudioResponse(BaseModel):
    assetId: Optional[str] = None
    audioPath: str
    totalDurationMs: int
    timingApproximate: bool = False
    words: list[dict]
    scenes: list[ReelAudioSceneResult]

class ReelBrollSceneInput(BaseModel):
    n: int
    brollQuery: str
    minSeconds: Optional[float] = 4.0
    durationMs: Optional[int] = None

class ReelBrollRequest(BaseModel):
    assetId: Optional[str] = None
    paperId: Optional[str] = None
    scenes: list[ReelBrollSceneInput]
    apiKey: Optional[str] = None

class ReelBrollResult(BaseModel):
    n: int
    query: str
    videoPath: Optional[str] = None
    reason: Optional[str] = None
    source: Optional[str] = None
    url: Optional[str] = None
    creator: Optional[str] = None
    pexelsId: Optional[str] = None
    pexelsUrl: Optional[str] = None
    photographer: Optional[str] = None
    photographerUrl: Optional[str] = None

class ReelBrollResponse(BaseModel):
    results: list[ReelBrollResult]
    scenes: Optional[list[ReelBrollResult]] = None

