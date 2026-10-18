"""Paper metadata must come from the PDF or a verified lookup, never from hardcoded defaults."""
from types import SimpleNamespace

from app.parse.meta import extract_meta_with_sources, parse_pdf_text

HALL_TEXT = """Maintenance of lost weight and long-term
management of obesity
Authors: Kevin D. Hall, Scott Kahan
Abstract: Weight loss can be achieved through a variety of modalities.
Introduction
Robert is a 47 year old patient who initially weighed 270 pounds."""

WILDING_TEXT = """Weight regain and cardiometabolic effects after
withdrawal of semaglutide: The
Authors: John P. H. Wilding, Rachel L. Batterham, Melanie Davies, Luc F. Van Gaal,
Thomas A.
Wadden,

Robert

F.

Kushner
Journal: Diabetes Obes Metab (2022) | DOI: 10.1111/dom.14725 | PMCID: PMC9542252
Abstract
Aim
To explore changes in body weight."""

HALL_TITLE = "Maintenance of lost weight and long-term management of obesity"
WILDING_TITLE = "Weight regain and cardiometabolic effects after withdrawal of semaglutide: The"


def _passages(title, text):
    return [SimpleNamespace(kind="title", page=1, text=title), SimpleNamespace(kind="paragraph", page=1, text=text)]


def _fake_http(routes):
    calls = []

    def get(url, params):
        calls.append((url, params))
        for key, val in routes.items():
            if key in url + str(params):
                return val
        return None
    get.calls = calls
    return get


CROSSREF_WILDING = {"message": {
    "DOI": "10.1111/dom.14725",
    "title": ["Weight regain and cardiometabolic effects after withdrawal of semaglutide: The\n <scp>STEP</scp>\n 1 trial extension"],
    "author": [{"family": "Wilding"}, {"family": "Batterham"}],
    "issued": {"date-parts": [[2022, 5, 19]]},
    "container-title": ["Diabetes, Obesity and Metabolism"]}}

CROSSREF_HALL_SEARCH = {"message": {"items": [
    {"DOI": "10.1002/oby.22399", "title": ["A Weight Loss Device That Looks Like a Pill"],
     "author": [{"family": "Kahan"}], "issued": {"date-parts": [[2019]]}},
    {"DOI": "10.1016/j.mcna.2017.08.012", "title": ["Maintenance of Lost Weight and Long-Term Management of Obesity"],
     "author": [{"family": "Hall"}, {"family": "Kahan"}], "issued": {"date-parts": [[2018, 1]]},
     "container-title": ["Medical Clinics of North America"]}]}}


def test_pdf_text_only_no_defaults():
    fields, sources = parse_pdf_text(HALL_TEXT, HALL_TITLE)
    assert fields["title"] == HALL_TITLE
    assert fields["authors"] == "Kevin D. Hall, Scott Kahan"
    # The Hall PDF states no year, journal or DOI, so none may be invented.
    assert fields["year"] is None and fields["journal"] is None and fields["doi"] is None


def test_wilding_pdf_fields_are_its_own():
    fields, sources = parse_pdf_text(WILDING_TEXT, WILDING_TITLE)
    assert fields["authors"].startswith("John P. H. Wilding, Rachel L. Batterham")
    assert fields["authors"].endswith("Robert F. Kushner")
    assert fields["journal"] == "Diabetes Obes Metab"
    assert fields["year"] == 2022
    assert fields["doi"] == "10.1111/dom.14725"
    assert "Hall" not in fields["authors"]


def test_lookup_disabled_leaves_unknowns_null():
    meta, _ = extract_meta_with_sources(_passages(HALL_TITLE, HALL_TEXT), lookup=False)
    assert meta.year is None and meta.journal is None and meta.doi is None
    assert meta.authors == "Kevin D. Hall, Scott Kahan"


def test_wilding_crossref_completes_truncated_title(tmp_path):
    get = _fake_http({"api.crossref.org/works/10.1111/dom.14725": CROSSREF_WILDING})
    meta, sources = extract_meta_with_sources(_passages(WILDING_TITLE, WILDING_TEXT), cache_dir=tmp_path, http_get=get)
    assert meta.title == "Weight regain and cardiometabolic effects after withdrawal of semaglutide: The STEP 1 trial extension"
    assert meta.journal == "Diabetes Obes Metab" and sources["journal"] == "pdf:journal-line"
    assert meta.year == 2022 and meta.doi == "10.1111/dom.14725"


def test_hall_crossref_and_pubmed_fill_missing(tmp_path):
    get = _fake_http({
        "query.bibliographic": CROSSREF_HALL_SEARCH,
        "esearch": {"esearchresult": {"idlist": ["29156185"]}},
        "esummary": {"result": {"29156185": {"source": "Med Clin North Am",
                                             "title": "Maintenance of Lost Weight and Long-Term Management of Obesity."}}},
    })
    meta, sources = extract_meta_with_sources(_passages(HALL_TITLE, HALL_TEXT), cache_dir=tmp_path, http_get=get)
    assert meta.doi == "10.1016/j.mcna.2017.08.012" and sources["doi"] == "crossref"
    assert meta.year == 2018
    assert meta.journal == "Med Clin North Am" and sources["journal"] == "pubmed:esummary"
    # Second run is served from the disk cache.
    n = len(get.calls)
    extract_meta_with_sources(_passages(HALL_TITLE, HALL_TEXT), cache_dir=tmp_path, http_get=get)
    assert len(get.calls) == n


def test_crossref_rejects_mismatched_author(tmp_path):
    wrong = {"message": dict(CROSSREF_WILDING["message"], author=[{"family": "Hall"}])}
    get = _fake_http({"api.crossref.org/works/": wrong})
    meta, _ = extract_meta_with_sources(_passages(WILDING_TITLE, WILDING_TEXT), cache_dir=tmp_path, http_get=get)
    assert meta.title == WILDING_TITLE  # lookup rejected; PDF values kept


def test_network_failure_keeps_nulls(tmp_path):
    meta, _ = extract_meta_with_sources(_passages(HALL_TITLE, HALL_TEXT), cache_dir=tmp_path, http_get=lambda u, p: None)
    assert meta.year is None and meta.doi is None and meta.journal is None
