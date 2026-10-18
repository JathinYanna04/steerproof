package com.steerclub.steerproof.pipeline;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class RenderServiceClient {

    private final RestClient restClient;

    public RenderServiceClient(
            RestClient.Builder restClientBuilder,
            @Value("${app.render-service.url:http://localhost:8002}") String renderServiceUrl
    ) {
        org.springframework.http.client.SimpleClientHttpRequestFactory requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(30));
        // A 40 s reel takes Remotion well over a minute on a laptop
        requestFactory.setReadTimeout(Duration.ofSeconds(600));

        this.restClient = restClientBuilder
                .baseUrl(renderServiceUrl)
                .defaultHeader("Accept", "application/json")
                .requestFactory(requestFactory)
                .build();
    }

    public RenderResult renderCarousel(String paperId, List<Map<String, Object>> slides, String citation, boolean draft) {
        return restClient.post()
                .uri("/carousel")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "paperId", paperId,
                        "slides", slides,
                        "citation", citation != null ? citation : "",
                        "draft", draft
                ))
                .retrieve()
                .body(RenderResult.class);
    }

    public RenderResult renderStatic(String paperId, Map<String, Object> staticContent, String citation, boolean draft) {
        return restClient.post()
                .uri("/static")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "paperId", paperId,
                        "static", staticContent,
                        "citation", citation != null ? citation : "",
                        "draft", draft
                ))
                .retrieve()
                .body(RenderResult.class);
    }

    public Map<String, Object> renderReel(String paperId, List<Map<String, Object>> scenes, String citation, boolean draft) {
        return restClient.post()
                .uri("/reel")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "paperId", paperId,
                        "scenes", scenes,
                        "citation", citation != null ? citation : "",
                        "draft", draft
                ))
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public record RenderBlock(Integer slide, String blockId, int x, int y, int w, int h) {}
    public record RenderResult(List<String> paths, List<RenderBlock> blocks) {}
}
