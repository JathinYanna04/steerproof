import pytest
from app.verify.number_check import check_numbers

def test_percent_spacing():
    quote = "Participants achieved a mean weight reduction of 10.0 % at week 48."
    claim = "Participants lost 10.0% of their body weight."
    ok, missing = check_numbers(claim, None, quote)
    assert ok is True
    assert missing == []

def test_unicode_minus():
    quote = "The change from baseline was −5.2 kg in the diet group."
    claim = "The change was -5.2 kg in the group."
    ok, missing = check_numbers(claim, None, quote)
    assert ok is True
    assert missing == []

def test_number_mismatch():
    quote = "The incidence rate was 11.6 per 1,000 person-years."
    claim = "The incidence rate was 11.8 per 1,000 person-years."
    ok, missing = check_numbers(claim, None, quote)
    assert ok is False
    assert "11.8" in missing

def test_written_numbers_skipped():
    quote = "Five patients experienced mild nausea."
    claim = "Five patients in the study had mild nausea."
    ok, missing = check_numbers(claim, None, quote)
    assert ok is True
    assert missing == []
