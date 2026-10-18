package com.steerclub.steerproof.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steerclub.steerproof.claim.ClaimEntity;
import com.steerclub.steerproof.common.AssetType;
import com.steerclub.steerproof.common.CitationHelper;
import com.steerclub.steerproof.common.DoctorStatus;
import com.steerclub.steerproof.common.FindingClass;
import com.steerclub.steerproof.common.Severity;
import com.steerclub.steerproof.paper.PaperEntity;
import com.steerclub.steerproof.pipeline.AssetContentEnricher;
import com.steerclub.steerproof.pipeline.MlServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;

/** WS1 accuracy rules: citations from the paper row, no citations on non-claims, qualifiers, stats, illustrations. */
class AccuracyRulesTest {

    static final String FACT_SENTENCE = "Your biology is actively signaling for fuel after calorie deficits.";

    private final ObjectMapper om = new ObjectMapper();
    private PolicyChecker checker;
    private MlServiceClient ml;
    private AssetContentEnricher enricher;

    @BeforeEach
    void setUp() {
        checker = new PolicyChecker(om);
        checker.loadRules();
        ml = Mockito.mock(MlServiceClient.class);
        enricher = new AssetContentEnricher(ml);
    }

    // ---------- fixtures ----------

    static PaperEntity paper(String id, String authors, int year, String journal) {
        PaperEntity p = new PaperEntity();
        p.setId(id);
        p.setAuthors(authors);
        p.setYear(year);
        p.setJournal(journal);
        return p;
    }

    static PaperEntity hall() {
        return paper("P-0001", "Kevin D. Hall, Scott Kahan", 2018, "Med Clin North Am");
    }

    static PaperEntity wilding() {
        return paper("P-0002", "John P. H. Wilding, Rachel L. Batterham, Melanie Davies", 2022, "Diabetes, Obesity and Metabolism");
    }

    static ClaimEntity claim(String id, FindingClass fc, String draft) {
        ClaimEntity c = new ClaimEntity();
        c.setId(id);
        c.setDoctorStatus(DoctorStatus.APPROVED);
        c.setFindingClass(fc);
        c.setConsumerDraft(draft);
        c.setScientificClaim(draft);
        c.setQuote(draft);
        return c;
    }

    static Map<String, Object> slide(int n, String type, String kind, List<String> ids, String headline, String body) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("n", n); s.put("type", type); s.put("kind", kind); s.put("claimIds", new ArrayList<>(ids));
        s.put("headline", headline); s.put("body", body);
        return s;
    }

    static Map<String, Object> scene(int n, String kind, List<String> ids, String onScreen, String voiceover, String motion) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("n", n); s.put("kind", kind); s.put("claimIds", new ArrayList<>(ids));
        s.put("onScreen", onScreen); s.put("voiceover", voiceover); s.put("motion", motion); s.put("brollQuery", "chai cup");
        return s;
    }

    static Map<AssetType, Map<String, Object>> fourAssets() {
        Map<String, Object> car = new LinkedHashMap<>();
        car.put("slides", new ArrayList<>(List.of(
                slide(1, "hook", "brand", List.of(), "Will the weight *come back*?", ""),
                slide(2, "point", "claim", List.of("C-1"), "Appetite rose about *100 kcal*", "For each kilo lost, appetite rose by about 100 kcal a day."),
                slide(3, "illustration", "illustration", List.of(), "The *4pm* chai", "Biscuits arrive at every meeting."),
                slide(4, "point", "claim", List.of("C-2"), "Across *29* studies", "Across 29 long-term studies, weight often returned."),
                slide(5, "source", "brand", List.of(), "Where this *comes* from", "A review of long-term studies."),
                slide(6, "closing", "brand", List.of(), "Doctor-led *care*", "steer · steerclub.com"))));
        Map<String, Object> sta = new LinkedHashMap<>(Map.of("headline", "Appetite *rises*", "support", "About 100 kcal a day per kilo lost.",
                "kind", "claim", "claimIds", List.of("C-1"), "footnote", "Hall and Kahan (2018)"));
        Map<String, Object> reel = new LinkedHashMap<>();
        reel.put("scenes", new ArrayList<>(List.of(
                scene(1, "brand", List.of(), "Will it come back?", "A common worry.", "title"),
                scene(2, "brand", List.of(), "It is biology, not *willpower*.", "Doctors plan for this.", "quote"),
                scene(3, "claim", List.of("C-1"), "About *100 kcal*", "Appetite rose by about 100 kcal a day per kilo lost.", "stat"),
                scene(4, "claim", List.of("C-2"), "29 studies", "Across 29 studies weight often returned.", "stat"),
                scene(5, "brand", List.of(), "steerclub.com", "Clinical weight loss, doctor-led. steerclub.com", "closing"))));
        Map<String, Object> cap = new LinkedHashMap<>();
        cap.put("paragraphs", new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of("text", "Appetite rose by about 100 kcal a day per kilo lost.", "kind", "claim", "claimIds", List.of("C-1"))),
                new LinkedHashMap<>(Map.of("text", "steerclub.com", "kind", "brand", "claimIds", List.of())))));
        cap.put("hashtags", List.of("#a", "#b", "#c", "#d", "#e", "#f", "#g", "#h"));
        Map<AssetType, Map<String, Object>> m = new LinkedHashMap<>();
        m.put(AssetType.CAROUSEL, car); m.put(AssetType.STATIC, sta); m.put(AssetType.REEL, reel); m.put(AssetType.CAPTION, cap);
        return m;
    }

    static Map<String, ClaimEntity> claims() {
        return Map.of(
                "C-1", claim("C-1", FindingClass.BACKGROUND, "Appetite rose by about 100 kcal per day for each kilogram lost."),
                "C-2", claim("C-2", FindingClass.BACKGROUND, "Across 29 long-term studies, weight often returned."));
    }

    private List<PolicyChecker.ViolationResult> check(AssetType type, Map<String, Object> content, Map<String, ClaimEntity> claims) {
        return checker.checkAsset("A-T", type.name(), om.valueToTree(content), claims, null);
    }

    private static boolean has(List<PolicyChecker.ViolationResult> v, String rule) {
        return v.stream().anyMatch(x -> rule.equals(x.rule()) && x.severity() == Severity.BLOCKING);
    }

    /** Every non-null "citation" string anywhere in the content tree. */
    private List<String> citations(Object node) {
        List<String> out = new ArrayList<>();
        JsonNode n = om.valueToTree(node);
        n.findValues("citation").forEach(c -> { if (!c.isNull()) out.add(c.asText()); });
        return out;
    }

    // ---------- 1.1 citations from the paper's own row ----------

    @Test
    void shortCitationBuiltFromRow() {
        assertEquals("Hall et al., 2018 · Med Clin North Am",
                CitationHelper.formatShortCitation("Kevin D. Hall, Scott Kahan", 2018, "Med Clin North Am"));
        assertEquals("Wilding et al., 2022 · Diabetes Obes Metab",
                CitationHelper.formatShortCitation("John P. H. Wilding, Rachel L. Batterham", 2022, "Diabetes, Obesity and Metabolism"));
        assertEquals("Wilding et al., 2022 · Diabetes Obes Metab",
                CitationHelper.formatShortCitation("Wilding JPH, Batterham RL, Davies M", 2022, "Diabetes Obes Metab"));
        // Missing metadata is omitted, never invented
        assertEquals("Hall et al.", CitationHelper.formatShortCitation("Kevin D. Hall, Scott Kahan", null, null));
        assertNull(CitationHelper.formatShortCitation(null, null, "Med Clin North Am"));
    }

    @Test
    void everyCitationOnEveryAssetMatchesItsOwnPaper() {
        for (PaperEntity p : List.of(hall(), wilding())) {
            String expected = AssetContentEnricher.paperCitation(p);
            String other = p.getId().equals("P-0001") ? "Wilding" : "Hall";
            Map<AssetType, Map<String, Object>> assets = fourAssets();
            assets.forEach((t, c) -> enricher.enrich(t, c, p, claims()));
            for (Map.Entry<AssetType, Map<String, Object>> e : assets.entrySet()) {
                List<String> cits = citations(e.getValue());
                assertFalse(cits.isEmpty(), e.getKey() + " has no citation");
                for (String c : cits) {
                    assertEquals(expected, c, p.getId() + " " + e.getKey());
                    assertFalse(c.contains(other), p.getId() + " cites " + other);
                }
            }
            assertFalse(assets.get(AssetType.STATIC).containsKey("footnote"), "writer footnote must be dropped");
        }
    }

    // ---------- 1.2 citations only on claims ----------

    @Test
    void citationOnlyOnClaimBlocksAndSourceSlide() {
        Map<AssetType, Map<String, Object>> assets = fourAssets();
        assets.forEach((t, c) -> enricher.enrich(t, c, wilding(), claims()));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slides = (List<Map<String, Object>>) assets.get(AssetType.CAROUSEL).get("slides");
        for (Map<String, Object> s : slides) {
            boolean expect = "claim".equals(s.get("kind")) || "source".equals(s.get("type"));
            assertEquals(expect, s.get("citation") != null, "slide " + s.get("n"));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scenes = (List<Map<String, Object>>) assets.get(AssetType.REEL).get("scenes");
        for (Map<String, Object> s : scenes) {
            assertEquals("claim".equals(s.get("kind")), s.get("citation") != null, "scene " + s.get("n"));
        }
        // Scene 2 was a brand line in a quote card: coerced to a title, no source line
        assertEquals("title", scenes.get(1).get("motion"));
        for (Map.Entry<AssetType, Map<String, Object>> e : assets.entrySet()) {
            assertFalse(has(check(e.getKey(), e.getValue(), claims()), "CITATION_ON_NONCLAIM"), e.getKey().name());
        }
    }

    @Test
    void citationOnNonClaimIsBlocking() {
        Map<String, Object> reel = new LinkedHashMap<>();
        Map<String, Object> s2 = scene(2, "brand", List.of(), "It is biology, not *willpower*.", "Doctors plan for this.", "quote");
        s2.put("citation", "Wilding et al., 2022 · Diabetes Obes Metab");
        reel.put("scenes", List.of(s2, scene(5, "brand", List.of(), "steerclub.com", "steerclub.com", "closing")));
        List<PolicyChecker.ViolationResult> v = check(AssetType.REEL, reel, claims());
        assertTrue(has(v, "CITATION_ON_NONCLAIM"));
        assertEquals(2, v.stream().filter(x -> "CITATION_ON_NONCLAIM".equals(x.rule())).count(), "citation + quote card");
    }

    @Test
    void citationTextInWriterCopyIsBlocking() {
        Map<String, Object> car = new LinkedHashMap<>();
        car.put("slides", List.of(
                slide(1, "source", "brand", List.of(), "Evidence from *Hall and Kahan (2018)*", ""),
                slide(2, "closing", "brand", List.of(), "Doctor-led", "steerclub.com")));
        assertTrue(has(check(AssetType.CAROUSEL, car, claims()), "CITATION_TEXT_IN_COPY"));
    }

    @Test
    void quoteCardShowsApprovedClaimCopy() {
        Map<String, ClaimEntity> cl = new HashMap<>(claims());
        ClaimEntity edited = claim("C-3", FindingClass.BACKGROUND, "Draft text.");
        edited.setEditedCopy("Doctor-edited copy.");
        cl.put("C-3", edited);
        Map<String, Object> reel = new LinkedHashMap<>();
        reel.put("scenes", new ArrayList<>(List.of(scene(2, "claim", List.of("C-3"), "Paraphrase", "Voice.", "quote"))));
        enricher.enrich(AssetType.REEL, reel, hall(), cl);
        @SuppressWarnings("unchecked")
        Map<String, Object> s = ((List<Map<String, Object>>) reel.get("scenes")).get(0);
        assertEquals("quote", s.get("motion"));
        assertEquals("Doctor-edited copy.", s.get("onScreen"));
        assertFalse(has(check(AssetType.REEL, reel, cl), "QUOTE_NOT_CLAIM_COPY"));

        s.put("onScreen", "Paraphrase");
        assertTrue(has(check(AssetType.REEL, reel, cl), "QUOTE_NOT_CLAIM_COPY"));
    }

    // ---------- 1.3 illustrations state no facts ----------

    @Test
    void illustrationStatingFactIsBlocking() {
        Map<String, Object> car = new LinkedHashMap<>();
        car.put("slides", new ArrayList<>(List.of(
                slide(3, "illustration", "illustration", List.of(), "After a long *day*", FACT_SENTENCE),
                slide(4, "illustration", "illustration", List.of(), "The *4pm* chai", "Biscuits arrive at every meeting."))));
        Mockito.when(ml.checkIllustrations(anyString(), anyList())).thenReturn(new MlServiceClient.IllustrationCheckResponse(List.of(
                new MlServiceClient.IllustrationVerdictDto("CAROUSEL/slide:3", true, "States that biology signals for fuel."),
                new MlServiceClient.IllustrationVerdictDto("CAROUSEL/slide:4", false, "Everyday routine.")), 1, 0));

        IllustrationFindings f = enricher.checkIllustrations("P-0001", Map.of(AssetType.CAROUSEL, car));
        Mockito.verify(ml, Mockito.times(1)).checkIllustrations(anyString(), anyList());
        List<PolicyChecker.ViolationResult> v = checker.checkAsset("A-T", "CAROUSEL", om.valueToTree(car), claims(), f);
        List<PolicyChecker.ViolationResult> facts = v.stream().filter(x -> "ILLUSTRATION_STATES_FACT".equals(x.rule())).toList();
        assertEquals(1, facts.size());
        assertEquals("slide:3", facts.get(0).location());
        assertEquals(Severity.BLOCKING, facts.get(0).severity());
    }

    @Test
    void illustrationCheckFailureFailsClosed() {
        Map<String, Object> car = new LinkedHashMap<>();
        car.put("slides", new ArrayList<>(List.of(slide(3, "illustration", "illustration", List.of(), "After a long *day*", FACT_SENTENCE))));
        Mockito.when(ml.checkIllustrations(anyString(), anyList())).thenThrow(new RuntimeException("503 UNAVAILABLE"));
        IllustrationFindings f = enricher.checkIllustrations("P-0001", Map.of(AssetType.CAROUSEL, car));
        assertNotNull(f.checkError());
        assertTrue(has(checker.checkAsset("A-T", "CAROUSEL", om.valueToTree(car), claims(), f), "ILLUSTRATION_STATES_FACT"));
    }

    // ---------- 1.4 qualifiers on exploratory claims in every format ----------

    static Map<String, ClaimEntity> exploratory() {
        return Map.of("C-0002-002", claim("C-0002-002", FindingClass.EXPLORATORY,
                "One year after stopping, nearly half still maintained at least 5% weight loss."));
    }

    @Test
    void missingQualifierOnReelSceneBothLines() {
        Map<String, Object> reel = new LinkedHashMap<>();
        reel.put("scenes", List.of(
                scene(4, "claim", List.of("C-0002-002"), "Nearly *half* maintained weight loss.",
                        "Yet, trial data showed nearly half the participants still maintained at least 5% weight loss one year later.", "title"),
                scene(5, "brand", List.of(), "steerclub.com", "steerclub.com", "closing")));
        List<PolicyChecker.ViolationResult> v = check(AssetType.REEL, reel, exploratory());
        assertTrue(v.stream().anyMatch(x -> "MISSING_QUALIFIER".equals(x.rule()) && x.location().equals("scene:4:onScreen")));
        assertTrue(v.stream().anyMatch(x -> "MISSING_QUALIFIER".equals(x.rule()) && x.location().equals("scene:4:voiceover")));
    }

    @Test
    void nonFactualHeadingMayRelyOnVoiceoverQualifier() {
        Map<String, Object> reel = new LinkedHashMap<>();
        reel.put("scenes", List.of(
                scene(4, "claim", List.of("C-0002-002"), "What the *trial* observed",
                        "In this trial, nearly half the participants still kept at least 5% off a year later.", "title"),
                scene(5, "brand", List.of(), "steerclub.com", "steerclub.com", "closing")));
        assertFalse(has(check(AssetType.REEL, reel, exploratory()), "MISSING_QUALIFIER"));

        // A factual onScreen needs its own qualifier even when the voiceover has one
        reel.put("scenes", List.of(
                scene(4, "claim", List.of("C-0002-002"), "Nearly *half* kept it off",
                        "In this trial, nearly half the participants still kept at least 5% off a year later.", "title")));
        assertTrue(has(check(AssetType.REEL, reel, exploratory()), "MISSING_QUALIFIER"));
    }

    @Test
    void missingQualifierInSlideStaticAndCaption() {
        Map<String, Object> car = new LinkedHashMap<>();
        car.put("slides", List.of(slide(2, "point", "claim", List.of("C-0002-002"), "Nearly *half* kept it off",
                "Nearly half kept at least 5% off a year later.")));
        assertTrue(has(check(AssetType.CAROUSEL, car, exploratory()), "MISSING_QUALIFIER"));

        Map<String, Object> sta = Map.of("headline", "In this trial, *half* kept it off", "support", "Nearly half kept at least 5% off.",
                "kind", "claim", "claimIds", List.of("C-0002-002"));
        assertTrue(has(check(AssetType.STATIC, sta, exploratory()), "MISSING_QUALIFIER"));

        Map<String, Object> cap = new LinkedHashMap<>();
        cap.put("paragraphs", List.of(Map.of("text", "Nearly half kept at least 5% off a year later.", "kind", "claim", "claimIds", List.of("C-0002-002"))));
        assertTrue(has(check(AssetType.CAPTION, cap, exploratory()), "MISSING_QUALIFIER"));

        cap.put("paragraphs", List.of(Map.of("text", "In this follow-up, nearly half kept at least 5% off a year later.", "kind", "claim", "claimIds", List.of("C-0002-002"))));
        assertFalse(has(check(AssetType.CAPTION, cap, exploratory()), "MISSING_QUALIFIER"));
    }

    // ---------- 1.5 stat eligibility ----------

    @Test
    void statEligibleOnlyForOutcomeValues() {
        Map<AssetType, Map<String, Object>> assets = fourAssets();
        assets.forEach((t, c) -> enricher.enrich(t, c, hall(), claims()));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slides = (List<Map<String, Object>>) assets.get(AssetType.CAROUSEL).get("slides");
        assertEquals(true, slides.get(1).get("statEligible"));
        assertEquals("100 kcal", slides.get(1).get("stat"));
        assertEquals(false, slides.get(3).get("statEligible"), "29 studies is a count, not an outcome");
        assertNull(slides.get(3).get("stat"));
        assertEquals(false, slides.get(2).get("statEligible"), "illustrations never get stats");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scenes = (List<Map<String, Object>>) assets.get(AssetType.REEL).get("scenes");
        assertEquals("stat", scenes.get(2).get("motion"));
        assertEquals("title", scenes.get(3).get("motion"), "count-only stat motion is coerced to title");
        assertEquals(true, assets.get(AssetType.STATIC).get("statEligible"));

        Map<String, Object> bm = AssetContentEnricher.blockMap(AssetType.CAROUSEL, assets.get(AssetType.CAROUSEL));
        assertTrue(bm.containsKey("slide2.stat"));
        assertFalse(bm.containsKey("slide4.stat"));
        assertEquals("brand", ((Map<?, ?>) bm.get("slide2.label")).get("kind"));
        assertEquals("brand", ((Map<?, ?>) bm.get("slide2.footer")).get("kind"));
    }
}
