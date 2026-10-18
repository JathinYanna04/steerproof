package com.steerclub.steerproof.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steerclub.steerproof.claim.ClaimEntity;
import com.steerclub.steerproof.common.DoctorStatus;
import com.steerclub.steerproof.common.FindingClass;
import com.steerclub.steerproof.common.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class PolicyCheckerTest {

    private PolicyChecker policyChecker;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        policyChecker = new PolicyChecker(objectMapper);
        policyChecker.loadRules();
    }

    private ClaimEntity makeClaim(String id, DoctorStatus status, FindingClass findingClass, String scientificClaim, String quote) {
        ClaimEntity claim = new ClaimEntity();
        claim.setId(id);
        claim.setDoctorStatus(status);
        claim.setFindingClass(findingClass);
        claim.setScientificClaim(scientificClaim);
        claim.setQuote(quote);
        return claim;
    }

    @Test
    void testDrugBrandRule() {
        ObjectNode staticNode = objectMapper.createObjectNode();
        staticNode.put("headline", "Information on Ozempic");
        staticNode.put("support", "Educational context");
        staticNode.put("footnote", "steerclub.com");
        staticNode.put("kind", "intro");

        List<PolicyChecker.ViolationResult> violations = policyChecker.checkAsset("A-1", "STATIC", staticNode, Map.of());
        assertTrue(violations.stream().anyMatch(v -> "DRUG_BRAND".equals(v.rule()) && v.severity() == Severity.BLOCKING));
    }

    @Test
    void testDrugGenericPromoRule() {
        ObjectNode staticNode = objectMapper.createObjectNode();
        staticNode.put("headline", "Semaglutide details");
        staticNode.put("support", "No drug in claim");
        staticNode.put("footnote", "steerclub.com");
        staticNode.put("kind", "claim");
        ArrayNode claimIds = staticNode.putArray("claimIds");
        claimIds.add("C-1");

        ClaimEntity claim = makeClaim("C-1", DoctorStatus.APPROVED, FindingClass.PRIMARY, "Diet intervention", "Diet was studied");
        Map<String, ClaimEntity> map = Map.of("C-1", claim);

        List<PolicyChecker.ViolationResult> violations = policyChecker.checkAsset("A-1", "STATIC", staticNode, map);
        assertTrue(violations.stream().anyMatch(v -> "DRUG_GENERIC_PROMO".equals(v.rule()) && v.severity() == Severity.WARNING));
    }

    @Test
    void testPromiseRule() {
        ObjectNode staticNode = objectMapper.createObjectNode();
        staticNode.put("headline", "Lose 10 kg guaranteed");
        staticNode.put("support", "In 4 weeks");
        staticNode.put("footnote", "steerclub.com");
        staticNode.put("kind", "intro");

        List<PolicyChecker.ViolationResult> violations = policyChecker.checkAsset("A-1", "STATIC", staticNode, Map.of());
        assertTrue(violations.stream().anyMatch(v -> "PROMISE".equals(v.rule()) && v.severity() == Severity.BLOCKING));
    }

    @Test
    void testHypeRule() {
        ObjectNode staticNode = objectMapper.createObjectNode();
        staticNode.put("headline", "A miracle breakthrough");
        staticNode.put("support", "Melt fat fast");
        staticNode.put("footnote", "steerclub.com");
        staticNode.put("kind", "intro");

        List<PolicyChecker.ViolationResult> violations = policyChecker.checkAsset("A-1", "STATIC", staticNode, Map.of());
        assertTrue(violations.stream().anyMatch(v -> "HYPE".equals(v.rule()) && v.severity() == Severity.BLOCKING));
    }

    @Test
    void testFearShameRule() {
        ObjectNode staticNode = objectMapper.createObjectNode();
        staticNode.put("headline", "Fat people are lazy");
        staticNode.put("support", "A ticking time bomb");
        staticNode.put("footnote", "steerclub.com");
        staticNode.put("kind", "intro");

        List<PolicyChecker.ViolationResult> violations = policyChecker.checkAsset("A-1", "STATIC", staticNode, Map.of());
        assertTrue(violations.stream().anyMatch(v -> "FEAR_SHAME".equals(v.rule()) && v.severity() == Severity.BLOCKING));
    }

    @Test
    void testBodyRule() {
        ObjectNode staticNode = objectMapper.createObjectNode();
        staticNode.put("headline", "Before and after results");
        staticNode.put("support", "Get a flat belly");
        staticNode.put("footnote", "steerclub.com");
        staticNode.put("kind", "intro");

        List<PolicyChecker.ViolationResult> violations = policyChecker.checkAsset("A-1", "STATIC", staticNode, Map.of());
        assertTrue(violations.stream().anyMatch(v -> "BODY".equals(v.rule()) && v.severity() == Severity.BLOCKING));
    }

    @Test
    void testAllCapsRule() {
        ObjectNode staticNode = objectMapper.createObjectNode();
        staticNode.put("headline", "SHOCKING results found");
        staticNode.put("support", "Normal text");
        staticNode.put("footnote", "steerclub.com");
        staticNode.put("kind", "intro");

        List<PolicyChecker.ViolationResult> violations = policyChecker.checkAsset("A-1", "STATIC", staticNode, Map.of());
        assertTrue(violations.stream().anyMatch(v -> "ALL_CAPS".equals(v.rule()) && v.severity() == Severity.BLOCKING));

        // Allowlist check (BMI, GLP-1 should not violate)
        ObjectNode allowedNode = objectMapper.createObjectNode();
        allowedNode.put("headline", "BMI was evaluated");
        allowedNode.put("support", "Normal text");
        allowedNode.put("footnote", "steerclub.com");
        allowedNode.put("kind", "intro");

        List<PolicyChecker.ViolationResult> allowedViolations = policyChecker.checkAsset("A-2", "STATIC", allowedNode, Map.of());
        assertFalse(allowedViolations.stream().anyMatch(v -> "ALL_CAPS".equals(v.rule())));
    }

    @Test
    void testLengthRule() {
        ObjectNode staticNode = objectMapper.createObjectNode();
        staticNode.put("headline", "This headline is way too long and clearly exceeds the forty-eight character limit specified in brand guidelines");
        staticNode.put("support", "Normal support");
        staticNode.put("footnote", "steerclub.com");
        staticNode.put("kind", "intro");

        List<PolicyChecker.ViolationResult> violations = policyChecker.checkAsset("A-1", "STATIC", staticNode, Map.of());
        assertTrue(violations.stream().anyMatch(v -> "LENGTH".equals(v.rule()) && v.severity() == Severity.BLOCKING));
    }

    @Test
    void testUncitedClaimRule() {
        ObjectNode staticNode = objectMapper.createObjectNode();
        staticNode.put("headline", "A factual claim statement");
        staticNode.put("support", "More details");
        staticNode.put("footnote", "steerclub.com");
        staticNode.put("kind", "claim");
        // Empty claimIds
        staticNode.putArray("claimIds");

        List<PolicyChecker.ViolationResult> violations = policyChecker.checkAsset("A-1", "STATIC", staticNode, Map.of());
        assertTrue(violations.stream().anyMatch(v -> "UNCITED_CLAIM".equals(v.rule()) && v.severity() == Severity.BLOCKING));

        // Non-approved claim
        ObjectNode staticNode2 = objectMapper.createObjectNode();
        staticNode2.put("headline", "A factual claim statement");
        staticNode2.put("support", "More details");
        staticNode2.put("footnote", "steerclub.com");
        staticNode2.put("kind", "claim");
        staticNode2.putArray("claimIds").add("C-REJECTED");

        ClaimEntity rejClaim = makeClaim("C-REJECTED", DoctorStatus.REJECTED, FindingClass.PRIMARY, "Claim", "Quote");
        List<PolicyChecker.ViolationResult> violations2 = policyChecker.checkAsset("A-2", "STATIC", staticNode2, Map.of("C-REJECTED", rejClaim));
        assertTrue(violations2.stream().anyMatch(v -> "UNCITED_CLAIM".equals(v.rule()) && v.severity() == Severity.BLOCKING));
    }

    @Test
    void testNumberMismatchRule() {
        ObjectNode staticNode = objectMapper.createObjectNode();
        staticNode.put("headline", "Weight decreased by 45%");
        staticNode.put("support", "Across all participants");
        staticNode.put("footnote", "steerclub.com");
        staticNode.put("kind", "claim");
        staticNode.putArray("claimIds").add("C-1");

        // Quote has 15%, not 45%
        ClaimEntity claim = makeClaim("C-1", DoctorStatus.APPROVED, FindingClass.PRIMARY, "Weight fell 15%", "Mean reduction was 15%");
        List<PolicyChecker.ViolationResult> violations = policyChecker.checkAsset("A-1", "STATIC", staticNode, Map.of("C-1", claim));
        assertTrue(violations.stream().anyMatch(v -> "NUMBER_MISMATCH".equals(v.rule()) && v.severity() == Severity.BLOCKING));
    }

    @Test
    void testIllustrationWithNumberRule() {
        ObjectNode staticNode = objectMapper.createObjectNode();
        staticNode.put("headline", "Graphic of 25% change");
        staticNode.put("support", "Normal text");
        staticNode.put("footnote", "steerclub.com");
        staticNode.put("kind", "illustration");

        List<PolicyChecker.ViolationResult> violations = policyChecker.checkAsset("A-1", "STATIC", staticNode, Map.of());
        assertTrue(violations.stream().anyMatch(v -> "ILLUSTRATION_WITH_NUMBER".equals(v.rule()) && v.severity() == Severity.BLOCKING));
    }

    @Test
    void testCertaintyOnExploratoryRule() {
        ObjectNode staticNode = objectMapper.createObjectNode();
        staticNode.put("headline", "Trial proves the effect");
        staticNode.put("support", "Firm outcome");
        staticNode.put("footnote", "steerclub.com");
        staticNode.put("kind", "claim");
        staticNode.putArray("claimIds").add("C-EXP");

        ClaimEntity claim = makeClaim("C-EXP", DoctorStatus.APPROVED, FindingClass.EXPLORATORY, "Exploratory signal", "Exploratory signal");
        List<PolicyChecker.ViolationResult> violations = policyChecker.checkAsset("A-1", "STATIC", staticNode, Map.of("C-EXP", claim));
        assertTrue(violations.stream().anyMatch(v -> "CERTAINTY_ON_EXPLORATORY".equals(v.rule()) && v.severity() == Severity.WARNING));
    }

    @Test
    void testCausalOnAssociationRule() {
        ObjectNode staticNode = objectMapper.createObjectNode();
        staticNode.put("headline", "Diet causes better sleep");
        staticNode.put("support", "Direct causal statement");
        staticNode.put("footnote", "steerclub.com");
        staticNode.put("kind", "claim");
        staticNode.putArray("claimIds").add("C-ASSOC");

        ClaimEntity claim = makeClaim("C-ASSOC", DoctorStatus.APPROVED, FindingClass.PRIMARY, "Diet was associated with sleep", "Diet was associated with sleep");
        List<PolicyChecker.ViolationResult> violations = policyChecker.checkAsset("A-1", "STATIC", staticNode, Map.of("C-ASSOC", claim));
        assertTrue(violations.stream().anyMatch(v -> "CAUSAL_ON_ASSOCIATION".equals(v.rule()) && v.severity() == Severity.WARNING));
    }

    @Test
    void testMissingSignoffRule() {
        ObjectNode carousel = objectMapper.createObjectNode();
        ArrayNode slides = carousel.putArray("slides");
        ObjectNode s1 = slides.addObject();
        s1.put("headline", "First slide");
        s1.put("body", "Body text");
        s1.put("kind", "intro");

        List<PolicyChecker.ViolationResult> violations = policyChecker.checkAsset("A-1", "CAROUSEL", carousel, Map.of());
        assertTrue(violations.stream().anyMatch(v -> "MISSING_SIGNOFF".equals(v.rule()) && v.severity() == Severity.BLOCKING));
    }

    @Test
    void testEmojiInAssetRule() {
        // Carousel slide with emoji
        ObjectNode carousel = objectMapper.createObjectNode();
        ArrayNode slides = carousel.putArray("slides");
        ObjectNode s1 = slides.addObject();
        s1.put("headline", "Hello \uD83D\uDE00 world");
        s1.put("body", "Body with steerclub.com");
        s1.put("kind", "intro");

        List<PolicyChecker.ViolationResult> violations = policyChecker.checkAsset("A-1", "CAROUSEL", carousel, Map.of());
        assertTrue(violations.stream().anyMatch(v -> "EMOJI_IN_ASSET".equals(v.rule()) && v.severity() == Severity.WARNING));

        // Caption with emoji should NOT flag EMOJI_IN_ASSET
        ObjectNode caption = objectMapper.createObjectNode();
        ArrayNode paras = caption.putArray("paragraphs");
        ObjectNode p1 = paras.addObject();
        p1.put("text", "Caption with \uD83D\uDE00 emoji steerclub.com");
        p1.put("kind", "intro");
        ArrayNode hashtags = caption.putArray("hashtags");
        for (int i = 0; i < 9; i++) hashtags.add("#tag" + i);

        List<PolicyChecker.ViolationResult> captionViolations = policyChecker.checkAsset("A-2", "CAPTION", caption, Map.of());
        assertFalse(captionViolations.stream().anyMatch(v -> "EMOJI_IN_ASSET".equals(v.rule())));
    }

    @Test
    void testInjectedBadAssetFiresAllTargetRulesAndBlocks() {
        // Text: "Lose 5 kg in 2 weeks, guaranteed! OZEMPIC"
        ObjectNode staticNode = objectMapper.createObjectNode();
        staticNode.put("headline", "Lose 5 kg in 2 weeks, guaranteed! OZEMPIC");
        staticNode.put("support", "Support copy");
        staticNode.put("footnote", "steerclub.com");
        staticNode.put("kind", "intro");

        List<PolicyChecker.ViolationResult> violations = policyChecker.checkAsset("A-BAD", "STATIC", staticNode, Map.of());

        Set<String> ruleCodes = new HashSet<>();
        boolean hasBlocking = false;
        System.out.println("=== Injected Bad Asset PolicyChecker Violations ===");
        for (PolicyChecker.ViolationResult v : violations) {
            System.out.println("Violation: rule=" + v.rule() + ", severity=" + v.severity() + ", location=" + v.location() + ", detail=" + v.detail());
            ruleCodes.add(v.rule());
            if (v.severity() == Severity.BLOCKING) {
                hasBlocking = true;
            }
        }
        System.out.println("Asset Status: " + (hasBlocking ? "BLOCKED" : "DRAFT"));

        assertTrue(ruleCodes.contains("DRUG_BRAND"), "DRUG_BRAND should fire for OZEMPIC");
        assertTrue(ruleCodes.contains("PROMISE"), "PROMISE should fire for Lose 5 kg in 2 weeks, guaranteed!");
        assertTrue(ruleCodes.contains("HYPE"), "HYPE should fire for guaranteed");
        assertTrue(ruleCodes.contains("ALL_CAPS"), "ALL_CAPS should fire for OZEMPIC");
        assertTrue(hasBlocking, "Asset status should be BLOCKED due to BLOCKING violations");
    }

    @Test
    void testCitationNumbersIgnoredInNumberMismatch() {
        // Carousel with source slide containing 2018, volume 102, pages 183
        ObjectNode carousel = objectMapper.createObjectNode();
        ArrayNode slides = carousel.putArray("slides");
        
        ObjectNode s1 = slides.addObject();
        s1.put("n", 1);
        s1.put("type", "hook");
        s1.put("kind", "brand");
        s1.put("headline", "Weight regain");
        s1.put("body", "Intro text");
        s1.putArray("claimIds");

        ObjectNode s6 = slides.addObject();
        s6.put("n", 6);
        s6.put("type", "source");
        s6.put("kind", "claim");
        s6.put("headline", "The evidence");
        s6.put("body", "Data from Hall and Kahan (2018), Med Clin North Am vol. 102, pp. 183-197.");
        s6.putArray("claimIds").add("C-SRC");

        ObjectNode s7 = slides.addObject();
        s7.put("n", 7);
        s7.put("type", "closing");
        s7.put("kind", "brand");
        s7.put("headline", "Closing");
        s7.put("body", "steerclub.com");
        s7.putArray("claimIds");

        // Claim has no 2018, 102, or 183
        ClaimEntity claim = makeClaim("C-SRC", DoctorStatus.APPROVED, FindingClass.PRIMARY, "Trial data", "Participants regained lost weight.");
        Map<String, ClaimEntity> claimsMap = Map.of("C-SRC", claim);

        List<PolicyChecker.ViolationResult> violations = policyChecker.checkAsset("A-CAR", "CAROUSEL", carousel, claimsMap);
        assertFalse(violations.stream().anyMatch(v -> "NUMBER_MISMATCH".equals(v.rule())),
                "NUMBER_MISMATCH should not fire on citation numbers in source slide");
    }
}

