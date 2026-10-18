package com.steerclub.steerproof.paper;

import com.steerclub.steerproof.common.PaperStatus;
import com.steerclub.steerproof.metric.MetricsController;
import com.steerclub.steerproof.pipeline.PipelineRunEntity;
import com.steerclub.steerproof.pipeline.PipelineRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PaperMetricsTest {

    @Autowired private PaperRepository paperRepository;
    @Autowired private PipelineRunRepository pipelineRunRepository;
    @Autowired private PaperController paperController;
    @Autowired private MetricsController metricsController;

    private final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

    private String paper(PaperStatus status) {
        String id = "P-TEST-MET-" + UUID.randomUUID().toString().substring(0, 8);
        paperRepository.save(new PaperEntity(id, "papers/test.pdf", status));
        return id;
    }

    private void run(String paperId, String stage, int startSec, int endSec, boolean ok, String model) {
        PipelineRunEntity r = new PipelineRunEntity(paperId, stage, t0.plusSeconds(startSec));
        r.setEndedAt(t0.plusSeconds(endSec));
        r.setOk(ok);
        r.setModel(model);
        if (!ok) r.setError("503 UNAVAILABLE");
        pipelineRunRepository.save(r);
    }

    @Test
    void runsReturnLatestRunPerStageInOrder() {
        String id = paper(PaperStatus.AWAITING_CLAIM_REVIEW);
        run(id, "PARSE", 0, 40, true, "docling");
        run(id, "EXTRACT", 40, 45, false, "gemini-x");
        run(id, "EXTRACT", 100, 160, true, "gemini-x");
        run(id, "VERIFY", 160, 250, true, "gemini-x");

        List<Map<String, Object>> runs = paperController.getRuns(id);
        assertEquals(List.of("PARSE", "EXTRACT", "VERIFY", "WRITE", "RENDER"), runs.stream().map(r -> r.get("stage")).toList());
        assertEquals("DONE", runs.get(1).get("status"));
        assertEquals(60.0, runs.get(1).get("seconds"));
        assertEquals("gemini-x", runs.get(1).get("model"));
        assertEquals("WAITING", runs.get(3).get("status"));
        assertNull(runs.get(3).get("startedAt"));
        assertTrue(runs.get(0).keySet().containsAll(List.of("stage", "status", "startedAt", "endedAt", "seconds", "model", "llmCalls", "cacheHits", "error")));

        Map<String, Object> stats = (Map<String, Object>) paperController.getPaper(id).get("stats");
        // Wall clock from the PARSE start to the latest VERIFY end, including the retry gap.
        assertEquals(250.0, stats.get("pipelineSeconds"));
        // Active time of the successful chain only: 40 + 60 + 90.
        assertEquals(190.0, stats.get("pipelineActiveSeconds"));
        assertNull(stats.get("writeRenderSeconds"));
    }

    @Test
    void failedAndRunningStagesAndWriteRenderSeconds() {
        String id = paper(PaperStatus.RENDERING);
        run(id, "PARSE", 0, 10, true, "docling");
        run(id, "VERIFY", 10, 20, true, "gemini-x");
        run(id, "WRITE", 1000, 1010, false, "gemini-x");
        run(id, "WRITE", 2000, 2030, true, "gemini-x");
        run(id, "RENDER", 2030, 2100, true, "playwright");

        List<Map<String, Object>> runs = paperController.getRuns(id);
        assertEquals("RUNNING", runs.get(4).get("status"));
        assertEquals("DONE", runs.get(3).get("status"));

        Map<String, Object> stats = (Map<String, Object>) paperController.getPaper(id).get("stats");
        assertEquals(100.0, stats.get("writeRenderSeconds"));

        String failedId = paper(PaperStatus.FAILED);
        run(failedId, "PARSE", 0, 10, false, "docling");
        Map<String, Object> parse = paperController.getRuns(failedId).get(0);
        assertEquals("FAILED", parse.get("status"));
        assertEquals("503 UNAVAILABLE", parse.get("error"));
    }

    @Test
    void metricsCsvHasHeaderAndOneRowPerPaper() {
        String id = paper(PaperStatus.AWAITING_CLAIM_REVIEW);
        String csv = metricsController.metricsCsv().getBody();
        assertNotNull(csv);
        assertTrue(csv.startsWith("paperId,title,status,claimsTotal,claimsAutoFlagged,"));
        assertTrue(csv.contains("\n" + id + ",,AWAITING_CLAIM_REVIEW,0,0,"));
    }
}
