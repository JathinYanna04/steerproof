package com.steerclub.steerproof.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class MlServiceClient {

    private final RestClient restClient;

    public MlServiceClient(
            RestClient.Builder restClientBuilder,
            @Value("${app.ml-service.url:http://localhost:8001}") String mlServiceUrl
    ) {
        org.springframework.http.client.SimpleClientHttpRequestFactory requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(java.time.Duration.ofSeconds(30));
        // The writer plus its one validation retry can take over 5 minutes on the free tier
        requestFactory.setReadTimeout(java.time.Duration.ofSeconds(600));

        this.restClient = restClientBuilder
                .baseUrl(mlServiceUrl)
                .defaultHeader("Accept", "application/json")
                .requestFactory(requestFactory)
                .build();
    }

    /** The Gemini model the running ml-service is configured with (all LLM stages use it), or null if unreachable. */
    public String geminiModel() {
        try {
            Map<?, ?> health = restClient.get().uri("/health").retrieve().body(Map.class);
            Object model = health != null ? health.get("gemini_model") : null;
            return model != null ? model.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public FetchUrlResponse fetchUrl(String paperId, String url) {
        return restClient.post()
                .uri("/fetch-url")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("paperId", paperId, "url", url))
                .retrieve()
                .body(FetchUrlResponse.class);
    }

    public ParseResponse parse(String paperId, String pdfPath) {
        return restClient.post()
                .uri("/parse")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("paperId", paperId, "pdfPath", pdfPath))
                .retrieve()
                .body(ParseResponse.class);
    }

    public ExtractResponse extract(String paperId, PaperMetaDto meta, List<PassageDto> passages, String theme) {
        Map<String, Object> body = Map.of(
                "paperId", paperId,
                "meta", meta != null ? meta : Map.of(),
                "passages", passages,
                "theme", theme != null ? theme : ""
        );
        return restClient.post()
                .uri("/extract")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(ExtractResponse.class);
    }

    public VerifyResponse verify(String paperId, List<VerifyClaimDto> claims, List<PassageDto> passages) {
        return restClient.post()
                .uri("/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("paperId", paperId, "claims", claims, "passages", passages))
                .retrieve()
                .body(VerifyResponse.class);
    }

    public WriteResponse write(String paperId, PaperMetaDto meta, List<ApprovedClaimDto> claims, String theme, String language, String only) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("paperId", paperId);
        body.put("meta", meta != null ? meta : java.util.Map.of());
        body.put("claims", claims);
        body.put("theme", theme != null ? theme : "");
        body.put("language", language != null ? language : "en");
        if (only != null) {
            body.put("only", only);
        }
        return restClient.post()
                .uri("/write")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(WriteResponse.class);
    }

    public IllustrationCheckResponse checkIllustrations(String paperId, List<IllustrationItemDto> items) {
        return restClient.post()
                .uri("/check/illustrations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("paperId", paperId, "items", items))
                .retrieve()
                .body(IllustrationCheckResponse.class);
    }

    /** Synthesises per-scene voiceover with edge-tts into storage/audio/{assetId}/scene-{n}.mp3 (+ word timings). */
    public Map<String, Object> reelAudio(String assetId, List<Map<String, Object>> scenes, String language) {
        return restClient.post()
                .uri("/reel/audio")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("assetId", assetId, "scenes", scenes, "language", language != null ? language : "en"))
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
    }

    /** Resolves Pixabay b-roll for all scenes of one reel in a single call (no clip id is reused within the reel). */
    public Map<String, Object> reelBroll(String assetId, String paperId, List<Map<String, Object>> scenes) {
        return restClient.post()
                .uri("/reel/broll")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("assetId", assetId, "paperId", paperId, "scenes", scenes))
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public record IllustrationItemDto(String id, String text) {}

    public record IllustrationVerdictDto(String id, Boolean statesFact, String reason) {}

    public record IllustrationCheckResponse(List<IllustrationVerdictDto> verdicts, int llmCalls, int cacheHits) {}

    public record ApprovedClaimDto(
            String claimId,
            String approvedCopy,
            String scientificClaim,
            String consumerDraft,
            String quote,
            String findingClass,
            List<NumberRefDto> numbers,
            List<String> limitations,
            String population
    ) {}

    public record WriteResponse(
            String coreMessage,
            String reasoning,
            Map<String, Object> carousel,
            @JsonProperty("static") Map<String, Object> staticBlock,
            Map<String, Object> reel,
            Map<String, Object> caption,
            int llmCalls,
            int cacheHits
    ) {}

    public record FetchUrlResponse(String pdfPath) {}

    public record PaperMetaDto(String title, String authors, Integer year, String journal, String doi) {}

    public record PassageDto(
            String id,
            String section,
            String kind,
            int page,
            Map<String, Double> bbox,
            Double page_width,
            Double page_height,
            String text,
            int ord
    ) {}

    public record NumberRefDto(String value, String unit, String context) {}

    public record ExtractedClaimDto(
            String passageId,
            String quote,
            String scientificClaim,
            String consumerDraft,
            List<NumberRefDto> numbers,
            String studyDesign,
            String population,
            String findingClass,
            List<String> limitations
    ) {}

    public record ParseResponse(
            PaperMetaDto meta,
            int pageCount,
            List<PassageDto> passages,
            List<String> pageImages
    ) {}

    public record ExtractResponse(
            List<ExtractedClaimDto> claims,
            int llmCalls,
            int cacheHits
    ) {}

    public record VerifyClaimDto(
            String passageId,
            String quote,
            String scientificClaim,
            String consumerDraft,
            List<NumberRefDto> numbers,
            String studyDesign,
            String population,
            String findingClass,
            List<String> limitations
    ) {}

    public record VerifyItemDetailDto(
            int index,
            String passageId,
            int quoteScore,
            boolean numbersOk,
            List<String> missingNumbers,
            String entailment,
            String risk,
            String riskReason,
            String populationFit,
            String populationReason
    ) {}

    public record VerifyResponse(
            List<VerifyItemDetailDto> results,
            int llmCalls,
            int cacheHits
    ) {}
}
