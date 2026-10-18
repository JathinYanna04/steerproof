"""Deterministic copy rules shared by the writer validator.

The backend PolicyChecker enforces the same rules (BLOCKING); these checks exist so the
writer can retry once with precise feedback instead of producing blocked assets.
Keep the lists in sync with PolicyChecker.java.
"""
import re

# Phrases that mark an EXPLORATORY finding as tentative / study-bound.
QUALIFIERS = [
    "in this trial", "in one trial", "in the trial",
    "in one study", "in this study", "in the study",
    "in this follow-up", "in one follow-up", "in the follow-up",
    "in this extension",
    "suggests", "suggest", "suggested",
    "may", "might",
    "on average",
]
_QUALIFIER_RE = re.compile(
    r"\b(?:" + "|".join(re.escape(q) for q in QUALIFIERS) + r")\b"
    # "in one clinical follow-up trial", "in this extension study", "in the exploratory analysis"
    r"|\bin (?:this|one|the|a) (?:[\w-]+ ){0,2}(?:trial|study|follow-up|extension|analysis)\b",
    re.IGNORECASE,
)

# A heading is "factual" if it carries a number, a quantity word or a finding verb.
_NUMBER_WORDS = r"half|most|majority|nearly|almost|percent|quarter|third|twice|double|fewer|more than|less than"
_FINDING_VERBS = (
    r"regain(?:ed|s)?|maintain(?:ed|s)?|kept|keep|lost|lose|losing|gain(?:ed|s)?|increas(?:ed|es|e)|"
    r"decreas(?:ed|es|e)|reduc(?:ed|es|e)|rose|rises?|fell|falls?|dropped|drops?|improv(?:ed|es|e)|"
    r"lower(?:ed|s)?|rais(?:ed|es|e)|return(?:ed|s)?|worsen(?:ed|s)?|declin(?:ed|es|e)|"
    r"led|cause[sd]?|triggers?|triggered|stay(?:ed|s)?|came back|comes back"
)
_FACTUAL_RE = re.compile(r"\d|\b(?:" + _NUMBER_WORDS + r"|" + _FINDING_VERBS + r")\b", re.IGNORECASE)

# Outcome units that justify the big-number stat treatment.
_STAT_RE = re.compile(
    r"\d+(?:\.\d+)?\s*(?:%|percent\b|kg\b|kilograms?\b|kilos?\b|kcal\b|calories\b|mmhg\b|mg/dl\b|"
    r"mmol/l\b|mmol/mol\b|lbs?\b|pounds\b|cm\b|bpm\b)",
    re.IGNORECASE,
)

# Citation text the writer must never produce (citations are built in code).
_CITATION_TEXT_RE = re.compile(r"\bet\s+al\b|\b(?:19|20)\d{2}\b|\bdoi\b", re.IGNORECASE)

# Cheap first-pass signal that an illustration states a biological / causal fact.
# The authoritative check is the Gemini illustration check (ILLUSTRATION_STATES_FACT).
_ILLUSTRATION_FACT_RE = re.compile(
    r"\b(?:biology|biological|hormones?|metabolism|metabolic|appetite|signal(?:s|ing|ling)?|"
    r"calorie|calories|deficits?|insulin|blood sugar|glucose|cholesterol|blood pressure|"
    r"your body|the body|causes?|because|leads? to|triggers?|burns?|fat)\b",
    re.IGNORECASE,
)


def has_qualifier(text: str) -> bool:
    return bool(text) and bool(_QUALIFIER_RE.search(text))


def is_factual(text: str) -> bool:
    """True unless the text is a non-factual heading (no number, no quantity word, no finding verb)."""
    return bool(text) and bool(_FACTUAL_RE.search(text.replace("*", "")))


def stat_value(text: str) -> str | None:
    m = _STAT_RE.search(text or "")
    return m.group(0).strip() if m else None


def has_citation_text(text: str) -> bool:
    return bool(text) and bool(_CITATION_TEXT_RE.search(text))


def illustration_fact_hint(text: str) -> str | None:
    m = _ILLUSTRATION_FACT_RE.search(text or "")
    return m.group(0) if m else None


def qualifier_errors(primary: str, secondary: str, where: str, primary_name: str, secondary_name: str) -> list[str]:
    """Qualifier rule for a block with a short primary line and a longer secondary line.

    Both lines need a qualifier, except a non-factual primary heading may rely on the
    secondary line's qualifier.
    """
    errors: list[str] = []
    sec_ok = not secondary or has_qualifier(secondary)
    if secondary and not has_qualifier(secondary):
        errors.append(f"{where}: {secondary_name} is linked to an EXPLORATORY claim and lacks a qualifier "
                      f"(e.g. 'in this trial', 'in one study', 'suggests', 'may', 'on average').")
    if primary and not has_qualifier(primary):
        if is_factual(primary) or not secondary or not sec_ok:
            errors.append(f"{where}: {primary_name} '{primary}' states an EXPLORATORY finding without a qualifier; "
                          f"add one (e.g. 'In this trial, ...') or make it a non-factual heading.")
    return errors


# Brand lines from the voice spec; they are not findings and never sit inside a claim block
# (mirrors PolicyChecker.BRAND_PHRASES in the backend).
BRAND_PHRASES = ["willpower", "doctor-led", "stop googling", "ask a doctor", "steer", "steerclub.com"]
_BRAND_RES = [(b, re.compile(r"\b" + re.escape(b) + r"\b")) for b in BRAND_PHRASES]


def brand_phrase(text: str) -> str | None:
    lower = text.replace("*", "").lower()
    return next((b for b, rx in _BRAND_RES if rx.search(lower)), None)
