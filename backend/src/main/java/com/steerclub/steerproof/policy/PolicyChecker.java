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
            "breakthrough", "instantly", "effortless", "guarantee", "guaranteed",
            "save lives", "saves lives", "life-saving", "lifesaving"
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

    /** Qualifiers that mark an EXPLORATORY finding as study-bound (MISSING_QUALIFIER). Keep in sync with ml-service copy_rules.py. */
    static final List<String> EXPLORATORY_QUALIFIERS = List.of(
            "in this trial", "in one trial", "in the trial",
            "in one study", "in this study", "in the study",
            "in this follow-up", "in one follow-up", "in the follow-up",
            "in this extension",
            "suggests", "suggest", "suggested", "may", "might", "on average"
    );
    private static final Pattern QUALIFIER_PATTERN = Pattern.compile(
            "(?i)\\b(?:" + String.join("|", EXPLORATORY_QUALIFIERS.stream().map(Pattern::quote).toList()) + ")\\b"
                    // "in one clinical follow-up trial", "in this extension study", "in the exploratory analysis"
                    + "|\\bin (?:this|one|the|a) (?:[\\w-]+ ){0,2}(?:trial|study|follow-up|extension|analysis)\\b");

    /**
     * Heuristic for the onScreen/headline exemption: a line is factual if it has a digit, a quantity word
     * or a finding verb. Only non-factual headings ("What the *trial* observed") may rely on the
     * qualifier in the longer line next to them (body / support / voiceover).
     */
    private static final Pattern FACTUAL_PATTERN = Pattern.compile(
            "(?i)\\d|\\b(?:half|most|majority|nearly|almost|percent|quarter|third|twice|double|fewer|more than|less than|"
                    + "regain(?:ed|s)?|maintain(?:ed|s)?|kept|keep|lost|lose|losing|gain(?:ed|s)?|increas(?:ed|es|e)|"
                    + "decreas(?:ed|es|e)|reduc(?:ed|es|e)|rose|rises?|fell|falls?|dropped|drops?|improv(?:ed|es|e)|"
                    + "lower(?:ed|s)?|rais(?:ed|es|e)|return(?:ed|s)?|worsen(?:ed|s)?|declin(?:ed|es|e)|"
                    + "led|cause[sd]?|triggers?|triggered|stay(?:ed|s)?|came back|comes back)\\b");

    /** Citation-like text the writer must never produce; citations are built in code. */
    private static final Pattern CITATION_TEXT_PATTERN = Pattern.compile(
            "(?i)\\bet\\s+al\\b|\\(\\s*(?:19|20)\\d{2}\\s*\\)|,\\s*(?:19|20)\\d{2}\\b|\\bdoi\\b");

    static final int QUOTE_MAX_CHARS = 140;

    /**
     * Deterministic backstop for ILLUSTRATION_STATES_FACT (the Gemini check is primary): an illustration that
     * talks about biology, hormones, signalling, metabolism or what the body does is stating a fact.
     */
    private static final Pattern ILLUSTRATION_FACT_PATTERN = Pattern.compile(
            "(?i)\\b(?:biology|biological|hormones?|signal(?:s|ing|ling)?|metabolism|insulin|blood sugar|glucose|"
                    + "cholesterol|blood pressure|calorie deficits?|(?:your|the) body (?:is|does|wants|needs|fights|tries|burns|stores))\\b");

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
        return checkAsset(assetId, assetType, content, claimsById, null);
    }

    /**
     * @param illustrationFindings result of the batched Gemini illustration check; null skips
     *                             ILLUSTRATION_STATES_FACT (e.g. re-checks of already-checked content)
     */
    public List<ViolationResult> checkAsset(
            String assetId,
            String assetType,
            JsonNode content,
            Map<String, ClaimEntity> claimsById,
            IllustrationFindings illustrationFindings
    ) {
        List<ViolationResult> violations = new ArrayList<>();
        checkAccuracy(assetType == null ? "" : assetType.toUpperCase(), content, claimsById, illustrationFindings, violations);

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
        if (!footnote.isBlank()) {
            violations.add(new ViolationResult("CITATION_TEXT_IN_COPY", Severity.BLOCKING, "static:footnote",
                    "Legacy writer footnote present; citations must come from the paper metadata row", null));
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

            int onScreenMax = "quote".equals(scene.path("motion").asText("")) ? QUOTE_MAX_CHARS : 40;
            if (onScreen.length() > onScreenMax) {
                violations.add(new ViolationResult("LENGTH", Severity.BLOCKING, loc + ":onScreen", "OnScreen exceeds " + onScreenMax + " chars: " + onScreen.length(), null));
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
                    boolean hasQualifier = hasQualifier(text);
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

    // ---------------------------------------------------------------------------------------------
    // Accuracy rules: CITATION_ON_NONCLAIM, CITATION_TEXT_IN_COPY, MISSING_QUALIFIER,
    // QUOTE_NOT_CLAIM_COPY, ILLUSTRATION_STATES_FACT. All BLOCKING.
    // ---------------------------------------------------------------------------------------------

    /** Brand lines from docs/BRAND.md / voice_spec.yaml (lower case, without markup). */
    static final List<String> BRAND_PHRASES = List.of(
            "willpower", "doctor-led", "stop googling", "ask a doctor", "steer", "steerclub.com");

    /** A copy block: its location, kind, linked claims, a short primary line and a longer secondary line. */
    private record Block(String loc, String kind, List<String> claimIds, String primary, String primaryName,
                         String secondary, String secondaryName, JsonNode node, boolean isSource) {}

    private List<Block> blocks(String type, JsonNode content) {
        List<Block> out = new ArrayList<>();
        if (content == null) return out;
        switch (type) {
            case "CAROUSEL" -> {
                JsonNode slides = content.has("slides") ? content.get("slides") : content;
                if (slides != null && slides.isArray()) {
                    for (int i = 0; i < slides.size(); i++) {
                        JsonNode s = slides.get(i);
                        int n = s.has("n") ? s.get("n").asInt() : i + 1;
                        out.add(new Block("slide:" + n, s.path("kind").asText("claim"), ids(s),
                                s.path("headline").asText(""), "headline", s.path("body").asText(""), "body", s,
                                "source".equalsIgnoreCase(s.path("type").asText(""))));
                    }
                }
            }
            case "STATIC" -> out.add(new Block("static", content.path("kind").asText("claim"), ids(content),
                    content.path("headline").asText(""), "headline", content.path("support").asText(""), "support", content, false));
            case "REEL" -> {
                JsonNode scenes = content.has("scenes") ? content.get("scenes") : content;
                if (scenes != null && scenes.isArray()) {
                    for (int i = 0; i < scenes.size(); i++) {
                        JsonNode s = scenes.get(i);
                        int n = s.has("n") ? s.get("n").asInt() : i + 1;
                        out.add(new Block("scene:" + n, s.path("kind").asText("claim"), ids(s),
                                s.path("onScreen").asText(""), "onScreen", s.path("voiceover").asText(""), "voiceover", s, false));
                    }
                }
            }
            case "CAPTION" -> {
                JsonNode paras = content.get("paragraphs");
                if (paras != null && paras.isArray()) {
                    for (int i = 0; i < paras.size(); i++) {
                        JsonNode p = paras.get(i);
                        out.add(new Block("caption:p" + (i + 1), p.path("kind").asText("claim"), ids(p),
                                p.path("text").asText(""), "text", "", null, p, false));
                    }
                }
            }
            default -> { }
        }
        return out;
    }

    private static List<String> ids(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n.has("claimIds") && n.get("claimIds").isArray()) {
            for (JsonNode c : n.get("claimIds")) out.add(c.asText());
        }
        return out;
    }

    static boolean hasQualifier(String text) {
        return text != null && QUALIFIER_PATTERN.matcher(text).find();
    }

    static boolean isFactual(String text) {
        return text != null && FACTUAL_PATTERN.matcher(text.replace("*", "")).find();
    }

    private void checkAccuracy(String type, JsonNode content, Map<String, ClaimEntity> claimsById,
                               IllustrationFindings findings, List<ViolationResult> violations) {
        boolean anyIllustration = false;
        for (Block b : blocks(type, content)) {
            boolean isClaim = "claim".equalsIgnoreCase(b.kind());
            String first = getFirstClaimId(b.claimIds());

            // CITATION_ON_NONCLAIM: citation lines and quote cards render only on claim blocks (and the source slide).
            String citation = b.node().path("citation").asText("");
            if (!isClaim && !b.isSource() && !citation.isBlank()) {
                violations.add(new ViolationResult("CITATION_ON_NONCLAIM", Severity.BLOCKING, b.loc(),
                        "Citation on a " + b.kind() + " block: " + citation, first));
            }
            if ("quote".equals(b.node().path("motion").asText("")) && !isClaim) {
                violations.add(new ViolationResult("CITATION_ON_NONCLAIM", Severity.BLOCKING, b.loc(),
                        "Quote card (with source line) on a " + b.kind() + " scene", first));
            }

            // QUOTE_NOT_CLAIM_COPY: a quote card must show the approved claim copy verbatim.
            if ("quote".equals(b.node().path("motion").asText("")) && isClaim) {
                boolean matches = b.claimIds().stream()
                        .map(claimsById::get)
                        .filter(Objects::nonNull)
                        .map(PolicyChecker::approvedCopy)
                        .anyMatch(c -> c != null && c.trim().equals(b.primary().trim()));
                if (!matches) {
                    violations.add(new ViolationResult("QUOTE_NOT_CLAIM_COPY", Severity.BLOCKING, b.loc(),
                            "Quote card text is not the approved claim copy: " + b.primary(), first));
                }
            }

            // CITATION_TEXT_IN_COPY: the writer must never produce author names, years or DOIs.
            for (String text : List.of(b.primary(), b.secondary())) {
                Matcher m = CITATION_TEXT_PATTERN.matcher(text);
                if (m.find()) {
                    violations.add(new ViolationResult("CITATION_TEXT_IN_COPY", Severity.BLOCKING, b.loc(),
                            "Citation-like text in copy ('" + m.group() + "'); citations are added from the paper metadata", first));
                    break;
                }
            }

            // BRAND_LINE_AS_CLAIM: voice-spec brand lines are not findings, so they never sit in a cited claim block.
            if (isClaim) {
                for (String text : List.of(b.primary(), b.secondary())) {
                    String lower = text.replace("*", "").toLowerCase(Locale.ROOT);
                    String hit = BRAND_PHRASES.stream()
                            .filter(bp -> Pattern.compile("\\b" + Pattern.quote(bp) + "\\b").matcher(lower).find())
                            .findFirst().orElse(null);
                    if (hit != null) {
                        violations.add(new ViolationResult("BRAND_LINE_AS_CLAIM", Severity.BLOCKING, b.loc(),
                                "Brand line '" + hit + "' in a cited claim block; make it a separate kind=brand block", first));
                        break;
                    }
                }
            }

            // MIXED_FINDING_CLASSES: background context (often the paper's introduction) next to a trial
            // finding in one block reads as if the trial found it. The writer is told to split them.
            boolean linksExploratory = b.claimIds().stream().map(claimsById::get)
                    .anyMatch(c -> c != null && c.getFindingClass() == FindingClass.EXPLORATORY);
            boolean linksBackground = b.claimIds().stream().map(claimsById::get)
                    .anyMatch(c -> c != null && c.getFindingClass() == FindingClass.BACKGROUND);
            if (linksExploratory && linksBackground) {
                violations.add(new ViolationResult("MIXED_FINDING_CLASSES", Severity.WARNING, b.loc(),
                        "Block links a BACKGROUND and an EXPLORATORY claim; check that background context is not presented as a trial finding", first));
            }

            // MISSING_QUALIFIER: every block linked to an EXPLORATORY claim, in every format.
            String exploratoryId = b.claimIds().stream()
                    .filter(cid -> claimsById.get(cid) != null && claimsById.get(cid).getFindingClass() == FindingClass.EXPLORATORY)
                    .findFirst().orElse(null);
            if (exploratoryId != null) {
                boolean secondaryPresent = b.secondary() != null && !b.secondary().isBlank();
                boolean secondaryOk = secondaryPresent && hasQualifier(b.secondary());
                if (secondaryPresent && !secondaryOk) {
                    violations.add(new ViolationResult("MISSING_QUALIFIER", Severity.BLOCKING, b.loc() + ":" + b.secondaryName(),
                            "Exploratory claim without a qualifier: " + b.secondary(), exploratoryId));
                }
                if (!b.primary().isBlank() && !hasQualifier(b.primary())
                        && (isFactual(b.primary()) || !secondaryOk)) {
                    violations.add(new ViolationResult("MISSING_QUALIFIER", Severity.BLOCKING, b.loc() + ":" + b.primaryName(),
                            "Exploratory claim without a qualifier: " + b.primary(), exploratoryId));
                }
            }

            // ILLUSTRATION_STATES_FACT (verdicts from the batched Gemini check)
            if ("illustration".equalsIgnoreCase(b.kind())) {
                Matcher fm = ILLUSTRATION_FACT_PATTERN.matcher(b.primary() + " " + b.secondary());
                if (fm.find()) {
                    violations.add(new ViolationResult("ILLUSTRATION_STATES_FACT", Severity.BLOCKING, b.loc(),
                            "Illustration uses '" + fm.group() + "', which states a biological/medical fact (keyword backstop)", null));
                }
            }
            if ("illustration".equalsIgnoreCase(b.kind()) && findings != null) {
                anyIllustration = true;
                String reason = findings.factualTexts().get(type + "/" + b.loc());
                if (reason != null) {
                    violations.add(new ViolationResult("ILLUSTRATION_STATES_FACT", Severity.BLOCKING, b.loc(),
                            "Illustration states or implies a medical/biological/causal fact: " + reason, null));
                }
            }
        }
        if (anyIllustration && findings.checkError() != null) {
            violations.add(new ViolationResult("ILLUSTRATION_STATES_FACT", Severity.BLOCKING, type.toLowerCase(),
                    "Illustration fact check could not run (" + findings.checkError() + "); regenerate to retry", null));
        }
    }

    private static String approvedCopy(ClaimEntity c) {
        if (c.getEditedCopy() != null && !c.getEditedCopy().isBlank()) return c.getEditedCopy();
        if (c.getConsumerDraft() != null && !c.getConsumerDraft().isBlank()) return c.getConsumerDraft();
        return c.getScientificClaim();
    }

    private String getFirstClaimId(List<String> claimIds) {
        return (claimIds != null && !claimIds.isEmpty()) ? claimIds.get(0) : null;
    }
}
