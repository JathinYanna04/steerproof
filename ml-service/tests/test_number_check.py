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

def test_range_normalization_to_vs_dash():
    # "20 to 40%" vs "20-40%"
    quote = "Patients expected to lose 20-40% of their starting body weight."
    claim = "Patients expected a 20 to 40% weight loss."
    ok, missing = check_numbers(claim, None, quote)
    assert ok is True
    assert missing == []

def test_range_normalization_dashes_spaces():
    # "20–40%" vs "20 — 40 %"
    quote = "Expected loss was 20–40%."
    claim = "Expected loss was 20 — 40 %."
    ok, missing = check_numbers(claim, None, quote)
    assert ok is True
    assert missing == []

def test_percent_vs_word_percent():
    # "%" vs "percent"
    quote = "A reduction of 15 percent was recorded."
    claim = "A 15% reduction was seen."
    ok, missing = check_numbers(claim, None, quote)
    assert ok is True
    assert missing == []

def test_word_half_does_not_satisfy_50_percent():
    # "half" -> "50%" must fail
    quote = "More than half of the lost weight was regained within two years."
    claim = "Patients regained 50% of the lost weight within two years."
    ok, missing = check_numbers(claim, None, quote)
    assert ok is False
    assert "50%" in missing

def test_week_120_comparison():
    # quote contains "week 120", draft says "by week 120" -> numbersOk = true
    quote = "Treatment was evaluated at week 120."
    draft = "Participants were assessed by week 120."
    ok, missing = check_numbers("", draft, quote)
    assert ok is True
    assert missing == []
