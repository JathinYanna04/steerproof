package com.steerclub.steerproof.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steerclub.steerproof.claim.ClaimEntity;
import com.steerclub.steerproof.common.DoctorStatus;
import com.steerclub.steerproof.common.FindingClass;
import com.steerclub.steerproof.common.Severity;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PolicyChecker {

    private final ObjectMapper objectMapper;
    private Map<String, Object> rulesConfig = new HashMap<>();

    private List<String> drugBrandWords = List.of(
            "ozempic", "wegovy", "rybelsus", "mounjaro", "zepbound",
            "saxenda", "victoza", "trulicity", "xenical", "alli",
            "contrave", "qsymia", "glycomet", "glucophage"
    );

    private List<String> drugGenericWords = List.of(
            "semaglutide", "tirzepatide", "liraglutide", "dulaglutide"
    );

    private List<Pattern> promisePatterns = List.of(
            Pattern.compile("(?i)\\b(?:lose|drop|shed)\\s+\\d+\\s*(?:kg|kilo|kilos|pounds|lbs)\\b"),
            Pattern.compile("(?i)\\bin\\s+\\d+\\s*(?:days|weeks)\\b"),
            Pattern.compile("(?i)\\bguarantee(?:d)?\\b"),
            Pattern.compile("(?i)\\bresults\\s+in\\b"),
            Pattern.compile("(?i)\\byou\\s+will\\s+lose\\b")
    );

    private List<String> hypeWords = List.of(
            "miracle", "magic", "secret", "hack", "cure", "cures",
            "melt fat", "detox", "burn fat fast", "revolutionary",
            "breakthrough", "instantly", "effortless", "guarantee", "guaranteed"
    );

    private List<String> fearShameWords = List.of(
            "lazy", "fat people", "obese people", "ugly", "disgusting",
            "no excuses", "you're killing", "ticking time bomb", "deadly"
    );

    private List<String> bodyWords = List.of(
            "before and after", "before/after", "bikini body", "beach body",
            "transformation", "flat belly"
    );

    private Set<String> allCapsAllowlist = Set.of(
            "GLP-1", "HBA1C", "BMI", "ICMR", "INDIAB", "DNA", "RCT",
            "STEP", "SURMOUNT", "PCOS", "WHO", "ICMR-INDIAB"
    );

    private List<String> certaintyWords = List.of(
            "proves", "always", "never", "will", "definitely", "shows that"
    );

    private List<String> qualifierWords = List.of(
            "suggests", "may", "in this study", "in the trial", "one study"
    );

    private List<String> associationWords = List.of(
            "associated", "linked", "correlated"
    );

    private List<String> causalWords = List.of(
            "causes", "leads to", "makes you", "because of"
    );

    private static final Pattern ALL_CAPS_PATTERN = Pattern.compile("\\b[A-Z]{4,}\\b");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+(?:\\.\\d+)?%?\\b");
    private static final Pattern PERCENT_OR_STAT_PATTERN = Pattern.compile("(?i)\\b\\d+(?:\\.\\d+)?\\s*(?:%|percent\\b|kg\\b|kilo\\b|kilos\\b)");
    private static final Pattern EMOJI_PATTERN = Pattern.compile("[\\x{1F300}-\\x{1F9FF}]|[\\x{2600}-\\x{26FF}]|[\\x{2700}-\\x{27BF}]");

    public PolicyChecker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void loadRules() {
        try {
            ClassPathResource resource = new ClassPathResource("policy/rules.yaml");
            if (resource.exists()) {
                Yaml yaml = new Yaml();
                try (InputStream in = resource.getInputStream()) {
                    Map<String, Object> data = yaml.load(in);
                    if (data != null && data.containsKey("rules")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> r = (Map<String, Object>) data.get("rules");
                        this.rulesConfig = r;
                    }
                }
            }
        } catch (Exception e) {
            // fallback to hardcoded lists
        }
    }

    public record ViolationResult(
            String rule,
            Severity severity,
            String location,
            String detail,
            String claimId
    ) {}

    public List<ViolationResult> checkAsset(
            String assetId,
            String assetType,
            JsonNode content,
            Map<String, ClaimEntity> claimsById
    ) {
        List<ViolationResult> violations = new ArrayList<>();

        if ("CAROUSEL".equalsIgnoreCase(assetType)) {
            checkCarousel(content, claimsById, violations);
        } else if ("STATIC".equalsIgnoreCase(assetType)) {
            checkStatic(content, claimsById, violations);
        } else if ("REEL".equalsIgnoreCase(assetType)) {
            checkReel(content, claimsById, violations);
        } else if ("CAPTION".equalsIgnoreCase(assetType)) {
            checkCaption(content, claimsById, violations);
        }

        return violations;
    }

    public void checkCarousel(JsonNode content, Map<String, ClaimEntity> claimsById, List<ViolationResult> violations) {
        JsonNode slides = content.has("slides") ? content.get("slides") : content;
        if (slides == null || !slides.isArray()) return;

        int totalSlides = slides.size();
        boolean hasSignoff = false;

        for (int i = 0; i < totalSlides; i++) {
            JsonNode slide = slides.get(i);
            int slideNum = slide.has("n") ? slide.get("n").asInt() : (i + 1);
            String loc = "slide:" + slideNum;

            String headline = slide.has("headline") ? slide.get("headline").asText("") : "";
            String body = slide.has("body") ? slide.get("body").asText("") : "";
            String kind = slide.has("kind") ? slide.get("kind").asText("claim") : "claim";

            List<String> claimIds = new ArrayList<>();
            if (slide.has("claimIds") && slide.get("claimIds").isArray()) {
                for (JsonNode cNode : slide.get("claimIds")) {
                    claimIds.add(cNode.asText());
                }
            }

            String slideType = slide.has("type") ? slide.get("type").asText("") : "";
            boolean isSourceSlide = "source".equalsIgnoreCase(slideType) || "source".equalsIgnoreCase(kind) || (slideNum == 6 && "source".equalsIgnoreCase(slideType));

            // Length limits
            if (headline.length() > 48) {
                violations.add(new ViolationResult("LENGTH", Severity.BLOCKING, loc + ":headline", "Headline exceeds 48 chars: " + headline.length(), null));
            }
            if (body.length() > 160) {
                violations.add(new ViolationResult("LENGTH", Severity.BLOCKING, loc + ":body", "Body exceeds 160 chars: " + body.length(), null));
            }

            // Check blocks for text rules
            checkText(headline, loc + ":headline", kind, claimIds, claimsById, violations, false, isSourceSlide);
            checkText(body, loc + ":body", kind, claimIds, claimsById, violations, false, isSourceSlide);

            if (i == totalSlides - 1) {
                if (headline.toLowerCase().contains("steerclub.com") || body.toLowerCase().contains("steerclub.com")) {
                    hasSignoff = true;
                }
            }
        }

        if (!hasSignoff && totalSlides > 0) {
            violations.add(new ViolationResult("MISSING_SIGNOFF", Severity.BLOCKING, "slide:" + totalSlides, "Last slide missing steerclub.com signoff", null));
        }
    }

    public void checkStatic(JsonNode content, Map<String, ClaimEntity> claimsById, List<ViolationResult> violations) {
        String headline = content.has("headline") ? content.get("headline").asText("") : "";
        String support = content.has("support") ? content.get("support").asText("") : "";
        String footnote = content.has("footnote") ? content.get("footnote").asText("") : "";
        String kind = content.has("kind") ? content.get("kind").asText("claim") : "claim";

        List<String> claimIds = new ArrayList<>();
        if (content.has("claimIds") && content.get("claimIds").isArray()) {
            for (JsonNode cNode : content.get("claimIds")) {
                claimIds.add(cNode.asText());
            }
        }

        if (headline.length() > 48) {
            violations.add(new ViolationResult("LENGTH", Severity.BLOCKING, "static:headline", "Headline exceeds 48 chars: " + headline.length(), null));
        }
        if (support.length() > 110) {
            violations.add(new ViolationResult("LENGTH", Severity.BLOCKING, "static:support", "Support exceeds 110 chars: " + support.length(), null));
        }
        if (footnote.length() > 90) {
            violations.add(new ViolationResult("LENGTH", Severity.BLOCKING, "static:footnote", "Footnote exceeds 90 chars: " + footnote.length(), null));
        }

        checkText(headline, "static:headline", kind, claimIds, claimsById, violations, false);
        checkText(support, "static:support", kind, claimIds, claimsById, violations, false);
        checkText(footnote, "static:footnote", "brand", List.of(), claimsById, violations, false);
    }

    public void checkReel(JsonNode content, Map<String, ClaimEntity> claimsById, List<ViolationResult> violations) {
        JsonNode scenes = content.has("scenes") ? content.get("scenes") : content;
        if (scenes == null || !scenes.isArray()) return;

        int totalScenes = scenes.size();
        boolean hasSignoff = false;

        for (int i = 0; i < totalScenes; i++) {
            JsonNode scene = scenes.get(i);
            int sceneNum = scene.has("n") ? scene.get("n").asInt() : (i + 1);
            String loc = "scene:" + sceneNum;

            String onScreen = scene.has("onScreen") ? scene.get("onScreen").asText("") : "";
            String voiceover = scene.has("voiceover") ? scene.get("voiceover").asText("") : "";
            String kind = scene.has("kind") ? scene.get("kind").asText("claim") : "claim";

            List<String> claimIds = new ArrayList<>();
            if (scene.has("claimIds") && scene.get("claimIds").isArray()) {
                for (JsonNode cNode : scene.get("claimIds")) {
                    claimIds.add(cNode.asText());
                }
            }

            if (onScreen.length() > 40) {
                violations.add(new ViolationResult("LENGTH", Severity.BLOCKING, loc + ":onScreen", "OnScreen exceeds 40 chars: " + onScreen.length(), null));
            }
            int wordCount = voiceover.trim().isEmpty() ? 0 : voiceover.trim().split("\\s+").length;
            if (wordCount > 30) {
                violations.add(new ViolationResult("LENGTH", Severity.BLOCKING, loc + ":voiceover", "Voiceover exceeds 30 words: " + wordCount, null));
            }

            checkText(onScreen, loc + ":onScreen", kind, claimIds, claimsById, violations, false);
            checkText(voiceover, loc + ":voiceover", kind, claimIds, claimsById, violations, true);

            if (i == totalScenes - 1) {
                if (onScreen.toLowerCase().contains("steerclub.com") || voiceover.toLowerCase().contains("steerclub.com")) {
                    hasSignoff = true;
                }
            }
        }

        if (!hasSignoff && totalScenes > 0) {
            violations.add(new ViolationResult("MISSING_SIGNOFF", Severity.BLOCKING, "scene:" + totalScenes, "Last reel scene missing steerclub.com signoff", null));
        }
    }

    public void checkCaption(JsonNode content, Map<String, ClaimEntity> claimsById, List<ViolationResult> violations) {
        JsonNode paras = content.has("paragraphs") ? content.get("paragraphs") : null;
        JsonNode hashtags = content.has("hashtags") ? content.get("hashtags") : null;

        int totalLen = 0;
        boolean hasSignoff = false;

        if (paras != null && paras.isArray()) {
            for (int i = 0; i < paras.size(); i++) {
                JsonNode p = paras.get(i);
                String text = p.has("text") ? p.get("text").asText("") : "";
                String kind = p.has("kind") ? p.get("kind").asText("claim") : "claim";
                totalLen += text.length();

                List<String> claimIds = new ArrayList<>();
                if (p.has("claimIds") && p.get("claimIds").isArray()) {
                    for (JsonNode cNode : p.get("claimIds")) {
                        claimIds.add(cNode.asText());
                    }
                }

                // In caption, emoji is allowed
                checkText(text, "caption:p" + (i + 1), kind, claimIds, claimsById, violations, true);

                if (text.toLowerCase().contains("steerclub.com")) {
                    hasSignoff = true;
                }
            }
        }

        if (totalLen > 1800) {
            violations.add(new ViolationResult("LENGTH", Severity.BLOCKING, "caption", "Caption total chars exceed 1800: " + totalLen, null));
        }

        if (hashtags != null && hashtags.isArray()) {
            int hCount = hashtags.size();
            if (hCount < 8 || hCount > 12) {
                violations.add(new ViolationResult("LENGTH", Severity.BLOCKING, "caption:hashtags", "Hashtag count must be between 8 and 12, found " + hCount, null));
            }
            for (JsonNode h : hashtags) {
                String tag = h.asText("");
                for (String drug : drugBrandWords) {
                    if (tag.toLowerCase().contains(drug)) {
                        violations.add(new ViolationResult("DRUG_BRAND", Severity.BLOCKING, "caption:hashtags", "Drug brand in hashtags: " + tag, null));
                    }
                }
            }
        }

        if (!hasSignoff) {
            violations.add(new ViolationResult("MISSING_SIGNOFF", Severity.BLOCKING, "caption", "Caption missing steerclub.com signoff", null));
        }
    }

    public void checkText(
            String text,
            String location,
            String kind,
            List<String> claimIds,
            Map<String, ClaimEntity> claimsById,
            List<ViolationResult> violations,
            boolean allowEmoji
    ) {
        checkText(text, location, kind, claimIds, claimsById, violations, allowEmoji, false);
    }

    public void checkText(
            String text,
            String location,
            String kind,
            List<String> claimIds,
            Map<String, ClaimEntity> claimsById,
            List<ViolationResult> violations,
            boolean allowEmoji,
            boolean isCitation
    ) {
        if (text == null || text.isBlank()) return;
        String textLower = text.toLowerCase();

        // 1. DRUG_BRAND
        for (String brand : drugBrandWords) {
            Pattern p = Pattern.compile("(?i)\\b" + Pattern.quote(brand) + "\\b");
            if (p.matcher(text).find()) {
                violations.add(new ViolationResult("DRUG_BRAND", Severity.BLOCKING, location, "Mention of drug brand: " + brand, getFirstClaimId(claimIds)));
            }
        }

        // 2. DRUG_GENERIC_PROMO
        for (String generic : drugGenericWords) {
            Pattern p = Pattern.compile("(?i)\\b" + Pattern.quote(generic) + "\\b");
            if (p.matcher(text).find()) {
                boolean inLinkedClaim = false;
                for (String cid : claimIds) {
                    ClaimEntity ce = claimsById.get(cid);
                    if (ce != null) {
                        String cText = ((ce.getScientificClaim() != null ? ce.getScientificClaim() : "") + " " +
                                       (ce.getQuote() != null ? ce.getQuote() : "") + " " +
                                       (ce.getEditedCopy() != null ? ce.getEditedCopy() : "")).toLowerCase();
                        if (cText.contains(generic.toLowerCase())) {
                            inLinkedClaim = true;
                            break;
                        }
                    }
                }
                if (!inLinkedClaim) {
                    violations.add(new ViolationResult("DRUG_GENERIC_PROMO", Severity.WARNING, location, "Generic medicine mention without same word in claim: " + generic, getFirstClaimId(claimIds)));
                }
            }
        }

        // 3. PROMISE
        for (Pattern pattern : promisePatterns) {
            Matcher m = pattern.matcher(text);
            if (m.find()) {
                violations.add(new ViolationResult("PROMISE", Severity.BLOCKING, location, "Promise found: " + m.group(), getFirstClaimId(claimIds)));
            }
        }

        // 4. HYPE
        for (String hype : hypeWords) {
            Pattern p = Pattern.compile("(?i)\\b" + Pattern.quote(hype) + "\\b");
            if (p.matcher(text).find()) {
                violations.add(new ViolationResult("HYPE", Severity.BLOCKING, location, "Hype phrase: " + hype, getFirstClaimId(claimIds)));
            }
        }

        // 5. FEAR_SHAME
        for (String bad : fearShameWords) {
            Pattern p = Pattern.compile("(?i)\\b" + Pattern.quote(bad) + "\\b");
            if (p.matcher(text).find()) {
                violations.add(new ViolationResult("FEAR_SHAME", Severity.BLOCKING, location, "Fear/shame language: " + bad, getFirstClaimId(claimIds)));
            }
        }

        // 6. BODY
        for (String body : bodyWords) {
            Pattern p = Pattern.compile("(?i)\\b" + Pattern.quote(body) + "\\b");
            if (p.matcher(text).find()) {
                violations.add(new ViolationResult("BODY", Severity.BLOCKING, location, "Body-shaming or before/after talk: " + body, getFirstClaimId(claimIds)));
            }
        }

        // 7. ALL_CAPS
        Matcher capsMatcher = ALL_CAPS_PATTERN.matcher(text);
        while (capsMatcher.find()) {
            String token = capsMatcher.group();
            if (!allCapsAllowlist.contains(token)) {
                violations.add(new ViolationResult("ALL_CAPS", Severity.BLOCKING, location, "All-caps token: " + token, getFirstClaimId(claimIds)));
            }
        }

        // 9. UNCITED_CLAIM
        if ("claim".equalsIgnoreCase(kind)) {
            if (claimIds == null || claimIds.isEmpty()) {
                violations.add(new ViolationResult("UNCITED_CLAIM", Severity.BLOCKING, location, "Claim block with empty claimIds", null));
            } else {
                for (String cid : claimIds) {
                    ClaimEntity ce = claimsById.get(cid);
                    if (ce == null || (ce.getDoctorStatus() != DoctorStatus.APPROVED && ce.getDoctorStatus() != DoctorStatus.EDITED)) {
                        violations.add(new ViolationResult("UNCITED_CLAIM", Severity.BLOCKING, location, "Claim not approved: " + cid, cid));
                    }
                }
            }
        }

        // 10. NUMBER_MISMATCH (ignores numbers inside citations)
        boolean isCitationBlock = isCitation
                || location.contains("footnote")
                || location.contains("citation")
                || location.contains("source");

        if ("claim".equalsIgnoreCase(kind) && claimIds != null && !claimIds.isEmpty() && !isCitationBlock) {
            Matcher numMatcher = NUMBER_PATTERN.matcher(text);
            while (numMatcher.find()) {
                String numToken = numMatcher.group().replace("%", "").trim();
                if (numToken.isEmpty()) continue;

                // Ignore 4-digit years (1900-2099)
                if (numToken.matches("^(?:19|20)\\d{2}$")) {
                    continue;
                }

                // Ignore numbers inside parentheses or citation contexts (e.g. "Hall (2018)", "vol. 12", "pp. 45-67")
                int start = numMatcher.start();
                int end = numMatcher.end();
                String prefix = text.substring(Math.max(0, start - 25), start).toLowerCase();
                String suffix = text.substring(end, Math.min(text.length(), end + 25)).toLowerCase();
                if (prefix.contains("et al") || prefix.contains("vol") || prefix.contains("pp.") || prefix.contains("page")
                        || (prefix.contains("(") && suffix.contains(")"))) {
                    continue;
                }

                boolean inAnyQuote = false;
                for (String cid : claimIds) {
                    ClaimEntity ce = claimsById.get(cid);
                    if (ce != null && ce.getQuote() != null) {
                        String cleanQuote = ce.getQuote().replace("−", "-").replace("–", "-");
                        if (cleanQuote.contains(numToken)) {
                            inAnyQuote = true;
                            break;
                        }
                    }
                }
                if (!inAnyQuote) {
                    violations.add(new ViolationResult("NUMBER_MISMATCH", Severity.BLOCKING, location, "Number not found in cited quote: " + numToken, getFirstClaimId(claimIds)));
                }
            }
        }

        // 11. ILLUSTRATION_WITH_NUMBER
        if ("illustration".equalsIgnoreCase(kind)) {
            Matcher m = PERCENT_OR_STAT_PATTERN.matcher(text);
            if (m.find()) {
                violations.add(new ViolationResult("ILLUSTRATION_WITH_NUMBER", Severity.BLOCKING, location, "Illustration contains statistic: " + m.group(), null));
            }
        }

        // 12. CERTAINTY_ON_EXPLORATORY
        if (claimIds != null) {
            for (String cid : claimIds) {
                ClaimEntity ce = claimsById.get(cid);
                if (ce != null && ce.getFindingClass() == FindingClass.EXPLORATORY) {
                    boolean hasCertainty = certaintyWords.stream().anyMatch(cw -> Pattern.compile("(?i)\\b" + Pattern.quote(cw) + "\\b").matcher(text).find());
                    boolean hasQualifier = qualifierWords.stream().anyMatch(qw -> Pattern.compile("(?i)\\b" + Pattern.quote(qw) + "\\b").matcher(text).find());
                    if (hasCertainty && !hasQualifier) {
                        violations.add(new ViolationResult("CERTAINTY_ON_EXPLORATORY", Severity.WARNING, location, "Certainty on exploratory claim without qualifier", cid));
                    }
                }
            }
        }

        // 13. CAUSAL_ON_ASSOCIATION
        if (claimIds != null) {
            for (String cid : claimIds) {
                ClaimEntity ce = claimsById.get(cid);
                if (ce != null) {
                    String claimText = ((ce.getScientificClaim() != null ? ce.getScientificClaim() : "") + " " +
                                       (ce.getQuote() != null ? ce.getQuote() : "")).toLowerCase();
                    boolean claimIsAssociation = associationWords.stream().anyMatch(claimText::contains);
                    boolean blockIsCausal = causalWords.stream().anyMatch(cw -> Pattern.compile("(?i)\\b" + Pattern.quote(cw) + "\\b").matcher(text).find());
                    if (claimIsAssociation && blockIsCausal) {
                        violations.add(new ViolationResult("CAUSAL_ON_ASSOCIATION", Severity.WARNING, location, "Causal language on associational claim", cid));
                    }
                }
            }
        }

        // 15. EMOJI_IN_ASSET
        if (!allowEmoji) {
            if (EMOJI_PATTERN.matcher(text).find()) {
                violations.add(new ViolationResult("EMOJI_IN_ASSET", Severity.WARNING, location, "Emoji found in post asset text", getFirstClaimId(claimIds)));
            }
        }
    }

    private String getFirstClaimId(List<String> claimIds) {
        return (claimIds != null && !claimIds.isEmpty()) ? claimIds.get(0) : null;
    }
}
