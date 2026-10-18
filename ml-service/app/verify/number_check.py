import re

NUM_REGEX = re.compile(r"(?<![\w.])[-−]?\d+(?:[.,]\d+)?\s?%?")

def normalize_number_str(s: str) -> str:
    # Remove all spaces, unify minus/dash, unify comma decimal to dot
    s = s.replace("\u2212", "-").replace("–", "-").replace(" ", "")
    # If ends with %
    has_percent = s.endswith("%")
    core = s[:-1] if has_percent else s
    # Convert comma to dot if comma is used as decimal separator (e.g. 5,2 -> 5.2)
    # Be careful not to break thousands separators if any, but in scientific claims decimals are common
    if "," in core and "." not in core:
        core = core.replace(",", ".")
    return core + ("%" if has_percent else "")

def extract_numbers(text: str) -> list[str]:
    if not text:
        return []
    matches = NUM_REGEX.findall(text)
    return [normalize_number_str(m) for m in matches]

def check_numbers(scientific_claim: str, consumer_draft: str | None, quote: str) -> tuple[bool, list[str]]:
    """
    Checks that every number mentioned in scientific_claim or consumer_draft
    appears in the quote.
    """
    norm_quote = quote.replace("\u2212", "-").replace("–", "-")
    # Also extract all normalized numbers from quote
    quote_numbers = set(extract_numbers(norm_quote))
    
    # Also allow simple substring check in normalized quote text
    clean_quote_str = "".join(norm_quote.replace(" ", "").replace(",", ".").split()).lower()
    
    claim_numbers = extract_numbers(scientific_claim)
    if consumer_draft:
        claim_numbers.extend(extract_numbers(consumer_draft))
        
    missing = []
    for num in claim_numbers:
        # Check if num is directly in quote_numbers or as substring in clean_quote_str
        if num in quote_numbers:
            continue
        
        # Check stripped version (e.g. 10.0% might be 10% or vice versa or 10.0 in text)
        clean_num = "".join(num.replace(" ", "").split()).lower()
        if clean_num in clean_quote_str:
            continue
            
        # Float numerical equivalence check against quote numbers
        try:
            num_val = float(num.rstrip("%"))
            matched_float = False
            for qn in quote_numbers:
                try:
                    if (num.endswith("%") == qn.endswith("%")) and abs(float(qn.rstrip("%")) - num_val) < 1e-5:
                        matched_float = True
                        break
                except ValueError:
                    pass
            if matched_float:
                continue
        except ValueError:
            pass

        missing.append(num)
        
    unique_missing = list(dict.fromkeys(missing))
    return len(unique_missing) == 0, unique_missing
