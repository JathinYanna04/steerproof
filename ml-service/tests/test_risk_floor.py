import pytest
from app.schemas import VerifyItem

def apply_risk_floor(entailment: str, initial_risk: str, numbers_ok: bool) -> str:
    risk = initial_risk
    if entailment == "UNSUPPORTED":
        risk = "RED"
    elif entailment == "OVERCLAIM" and risk == "GREEN":
        risk = "AMBER"

    if not numbers_ok and risk == "GREEN":
        risk = "AMBER"

    return risk

def test_overclaim_elevates_green_to_amber():
    assert apply_risk_floor("OVERCLAIM", "GREEN", numbers_ok=True) == "AMBER"
    assert apply_risk_floor("OVERCLAIM", "RED", numbers_ok=True) == "RED"

def test_unsupported_elevates_to_red():
    assert apply_risk_floor("UNSUPPORTED", "GREEN", numbers_ok=True) == "RED"
    assert apply_risk_floor("UNSUPPORTED", "AMBER", numbers_ok=True) == "RED"

def test_numbers_not_ok_elevates_green_to_amber():
    assert apply_risk_floor("SUPPORTED", "GREEN", numbers_ok=False) == "AMBER"
    assert apply_risk_floor("SUPPORTED", "RED", numbers_ok=False) == "RED"

def test_supported_clean_remains_green():
    assert apply_risk_floor("SUPPORTED", "GREEN", numbers_ok=True) == "GREEN"
