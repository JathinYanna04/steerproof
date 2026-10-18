package com.steerclub.steerproof.pipeline;

import com.steerclub.steerproof.claim.ClaimEntity;
import com.steerclub.steerproof.common.AssetType;
import com.steerclub.steerproof.common.CitationHelper;
import com.steerclub.steerproof.paper.PaperEntity;
import com.steerclub.steerproof.policy.IllustrationFindings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds the code-owned fields to writer output before policy checks and rendering:
 * citation (from the paper's own metadata row, only on claim blocks and the source slide),
 * statEligible/stat (outcome values only), and reel motion coercion (quote/stat).
 * The writer LLM never produces any of these.
 */
@Component
public class AssetContentEnricher {

    private static final Logger log = LoggerFactory.getLogger(AssetContentEnricher.class);

    /** Outcome units that justify the big-number treatment. Counts of studies/people/years never match. */
    static final Pattern STAT_PATTERN = Pattern.compile(
            "\\d+(?:\\.\\d+)?\\s*(?:%|percent\\b|kg\\b|kilograms?\\b|kilos?\\b|kcal\\b|calories\\b|mmhg\\b|mg/dl\\b|"
                    + "mmol/l\\b|mmol/mol\\b|lbs?\\b|pounds\\b|cm\\b|bpm\\b)",
            Pattern.CASE_INSENSITIVE);

    /** Quote cards have room for a full approved claim sentence. */
    static final int QUOTE_MAX_CHARS = 140;

    private final MlServiceClient mlServiceClient;

    public AssetContentEnricher(MlServiceClient mlServiceClient) {
        this.mlServiceClient = mlServiceClient;
    }

    public static String statValue(String text) {
        if (text == null) return null;
        Matcher m = STAT_PATTERN.matcher(text);
        return m.find() ? m.group().trim() : null;
    }

    /** Approved copy for a claim: the doctor's edited copy if present, else the consumer draft. */
    public static String approvedCopy(ClaimEntity c) {
        if (c == null) return null;
        if (c.getEditedCopy() != null && !c.getEditedCopy().isBlank()) return c.getEditedCopy();
        if (c.getConsumerDraft() != null && !c.getConsumerDraft().isBlank()) return c.getConsumerDraft();
        return c.getScientificClaim();
    }

    public static String paperCitation(PaperEntity paper) {
        return CitationHelper.formatShortCitation(paper.getAuthors(), paper.getYear(), paper.getJournal());
    }

    @SuppressWarnings("unchecked")
    public void enrich(AssetType type, Map<String, Object> content, PaperEntity paper, Map<String, ClaimEntity> claimsById) {
        if (content == null) return;
        String citation = paperCitation(paper);
        content.put("citation", citation);

        switch (type) {
            case CAROUSEL -> {
                for (Map<String, Object> slide : list(content.get("slides"))) {
                    boolean isClaim = "claim".equals(slide.get("kind"));
                    boolean isSource = "source".equals(slide.get("type"));
                    slide.put("citation", (isClaim || isSource) ? citation : null);
                    putStat(slide, isClaim, str(slide.get("headline")) + " " + str(slide.get("body")));
                }
            }
            case STATIC -> {
                content.remove("footnote");
                boolean isClaim = "claim".equals(content.get("kind"));
                content.put("citation", isClaim ? citation : null);
                content.put("paperCitation", citation);
                putStat(content, isClaim, str(content.get("headline")) + " " + str(content.get("support")));
            }
            case REEL -> {
                for (Map<String, Object> scene : list(content.get("scenes"))) {
                    boolean isClaim = "claim".equals(scene.get("kind"));
                    scene.put("citation", isClaim ? citation : null);
                    putStat(scene, isClaim, str(scene.get("onScreen")) + " " + str(scene.get("voiceover")));
                    coerceMotion(scene, isClaim, claimsById);
                }
            }
            case CAPTION -> {
                for (Map<String, Object> p : list(content.get("paragraphs"))) {
                    p.put("citation", "claim".equals(p.get("kind")) ? citation : null);
                }
            }
        }
    }

    private void putStat(Map<String, Object> block, boolean isClaim, String text) {
        String value = isClaim ? statValue(text) : null;
        block.put("statEligible", value != null);
        block.put("stat", value);
    }

    @SuppressWarnings("unchecked")
    private void coerceMotion(Map<String, Object> scene, boolean isClaim, Map<String, ClaimEntity> claimsById) {
        String motion = str(scene.get("motion"));
        if ("stat".equals(motion) && !Boolean.TRUE.equals(scene.get("statEligible"))) {
            scene.put("motion", "title");
            scene.put("motionCoercedFrom", "stat");
        } else if ("quote".equals(motion)) {
            String copy = null;
            if (isClaim && scene.get("claimIds") instanceof List<?> ids && !ids.isEmpty()) {
                copy = approvedCopy(claimsById.get(String.valueOf(ids.get(0))));
            }
            if (copy != null && copy.length() <= QUOTE_MAX_CHARS) {
                // A quote card shows the approved claim copy verbatim, never writer paraphrase.
                scene.put("onScreen", copy);
            } else {
                scene.put("motion", "title");
                scene.put("motionCoercedFrom", "quote");
            }
        }
    }

    /**
     * One batched ILLUSTRATION_STATES_FACT check over every illustration block of all given assets.
     * Item ids are "<ASSET_TYPE>/<location>", e.g. "CAROUSEL/slide:3". Failures are returned, not swallowed.
     */
    public IllustrationFindings checkIllustrations(String paperId, Map<AssetType, Map<String, Object>> contents) {
        List<MlServiceClient.IllustrationItemDto> items = new ArrayList<>();
        for (Map.Entry<AssetType, Map<String, Object>> e : contents.entrySet()) {
            items.addAll(illustrationItems(e.getKey(), e.getValue()));
        }
        if (items.isEmpty()) return IllustrationFindings.none();
        try {
            MlServiceClient.IllustrationCheckResponse res = mlServiceClient.checkIllustrations(paperId, items);
            if (res == null || res.verdicts() == null) {
                return IllustrationFindings.failed("illustration check returned no verdicts");
            }
            Map<String, String> factual = new HashMap<>();
            for (MlServiceClient.IllustrationVerdictDto v : res.verdicts()) {
                if (Boolean.TRUE.equals(v.statesFact())) factual.put(v.id(), v.reason());
            }
            for (MlServiceClient.IllustrationItemDto item : items) {
                boolean answered = res.verdicts().stream().anyMatch(v -> item.id().equals(v.id()));
                if (!answered) factual.put(item.id(), "Checker returned no verdict for this item.");
            }
            return new IllustrationFindings(factual, null, res.llmCalls(), res.cacheHits());
        } catch (Exception ex) {
            log.error("Illustration fact check failed for {}: {}", paperId, ex.getMessage());
            return IllustrationFindings.failed(ex.getMessage());
        }
    }

    public static List<MlServiceClient.IllustrationItemDto> illustrationItems(AssetType type, Map<String, Object> content) {
        List<MlServiceClient.IllustrationItemDto> items = new ArrayList<>();
        if (content == null) return items;
        String prefix = type.name() + "/";
        switch (type) {
            case CAROUSEL -> {
                int i = 0;
                for (Map<String, Object> s : list(content.get("slides"))) {
                    i++;
                    if ("illustration".equals(s.get("kind"))) {
                        items.add(new MlServiceClient.IllustrationItemDto(prefix + "slide:" + num(s.get("n"), i),
                                join(str(s.get("headline")), str(s.get("body")))));
                    }
                }
            }
            case STATIC -> {
                if ("illustration".equals(content.get("kind"))) {
                    items.add(new MlServiceClient.IllustrationItemDto(prefix + "static",
                            join(str(content.get("headline")), str(content.get("support")))));
                }
            }
            case REEL -> {
                int i = 0;
                for (Map<String, Object> s : list(content.get("scenes"))) {
                    i++;
                    if ("illustration".equals(s.get("kind"))) {
                        items.add(new MlServiceClient.IllustrationItemDto(prefix + "scene:" + num(s.get("n"), i),
                                join(str(s.get("onScreen")), str(s.get("voiceover")))));
                    }
                }
            }
            case CAPTION -> {
                int i = 0;
                for (Map<String, Object> p : list(content.get("paragraphs"))) {
                    i++;
                    if ("illustration".equals(p.get("kind"))) {
                        items.add(new MlServiceClient.IllustrationItemDto(prefix + "caption:p" + i, str(p.get("text"))));
                    }
                }
            }
        }
        return items;
    }

    /**
     * Click-to-source map for rendered blocks. Copy blocks carry the block's kind + claimIds;
     * labels, footers and the citation line are kind=brand so they are never claim-clickable.
     */
    public static Map<String, Object> blockMap(AssetType type, Map<String, Object> content) {
        Map<String, Object> map = new HashMap<>();
        Map<String, Object> brand = Map.of("kind", "brand", "claimIds", List.of());
        if (type == AssetType.CAROUSEL) {
            int i = 0;
            for (Map<String, Object> s : list(content.get("slides"))) {
                i++;
                int n = num(s.get("n"), i);
                Map<String, Object> meta = Map.of("kind", str(s.get("kind")).isEmpty() ? "brand" : str(s.get("kind")),
                        "claimIds", s.get("claimIds") instanceof List<?> l ? l : List.of());
                map.put("slide" + n + ".headline", meta);
                map.put("slide" + n + ".body", meta);
                if (Boolean.TRUE.equals(s.get("statEligible"))) map.put("slide" + n + ".stat", meta);
                map.put("slide" + n + ".label", brand);
                map.put("slide" + n + ".footer", brand);
            }
        } else if (type == AssetType.STATIC) {
            Map<String, Object> meta = Map.of("kind", str(content.get("kind")).isEmpty() ? "claim" : str(content.get("kind")),
                    "claimIds", content.get("claimIds") instanceof List<?> l ? l : List.of());
            map.put("static.headline", meta);
            map.put("static.support", meta);
            if (Boolean.TRUE.equals(content.get("statEligible"))) map.put("static.stat", meta);
            map.put("static.label", brand);
            map.put("static.footer", brand);
            map.put("static.footnote", brand);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object x : l) if (x instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int num(Object o, int fallback) {
        return o instanceof Number n ? n.intValue() : fallback;
    }

    private static String join(String a, String b) {
        return (a + " " + b).replace("*", "").trim();
    }
}
