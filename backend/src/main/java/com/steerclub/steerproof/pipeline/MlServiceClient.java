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
        requestFactory.setReadTimeout(java.time.Duration.ofSeconds(300));

        this.restClient = restClientBuilder
                .baseUrl(mlServiceUrl)
                .defaultHeader("Accept", "application/json")
                .requestFactory(requestFactory)
                .build();
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
