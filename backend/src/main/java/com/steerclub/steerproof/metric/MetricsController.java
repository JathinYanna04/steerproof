package com.steerclub.steerproof.metric;

import com.steerclub.steerproof.paper.PaperEntity;
import com.steerclub.steerproof.paper.PaperRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MetricsController {

    private static final List<String> COLUMNS = List.of(
            "claimsTotal", "claimsAutoFlagged", "approved", "edited", "rejected", "pending", "needsEvidence",
            "reviewSecondsTotal", "humanSecondsTotal", "pipelineSeconds", "pipelineActiveSeconds", "writeRenderSeconds");

    private final PaperRepository paperRepository;
    private final PaperMetricsService metricsService;

    public MetricsController(PaperRepository paperRepository, PaperMetricsService metricsService) {
        this.paperRepository = paperRepository;
        this.metricsService = metricsService;
    }

    /** One row per paper; empty cells mean the value is unknown (e.g. the stage never completed). */
    @GetMapping(value = "/metrics.csv", produces = "text/csv")
    public ResponseEntity<String> metricsCsv() {
        StringBuilder sb = new StringBuilder("paperId,title,status," + String.join(",", COLUMNS) + "\n");
        for (PaperEntity p : paperRepository.findAllByOrderByCreatedAtDesc()) {
            Map<String, Object> stats = metricsService.stats(p.getId());
            sb.append(csv(p.getId())).append(',').append(csv(p.getTitle())).append(',').append(csv(p.getStatus().name()));
            for (String c : COLUMNS) {
                Object v = stats.get(c);
                sb.append(',').append(v == null ? "" : v.toString());
            }
            sb.append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=metrics.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(sb.toString());
    }

    private static String csv(String s) {
        if (s == null) return "";
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
