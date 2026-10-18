package com.steerclub.steerproof.metric;

import com.steerclub.steerproof.claim.ClaimEntity;
import com.steerclub.steerproof.claim.ClaimRepository;
import com.steerclub.steerproof.common.DoctorStatus;
import com.steerclub.steerproof.common.PaperStatus;
import com.steerclub.steerproof.common.Risk;
import com.steerclub.steerproof.pipeline.PipelineRunEntity;
import com.steerclub.steerproof.pipeline.PipelineRunRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Per-paper stage status and metrics, computed from pipeline_runs, claims and human_events.
 *
 * pipelineSeconds: wall clock of the latest successful chain, anchored on the latest successful VERIFY:
 *   from the latest successful PARSE that started before it, to that VERIFY's end. It includes any wait
 *   between stages (e.g. a failed stage that was retried later).
 * pipelineActiveSeconds: the summed durations of that chain's PARSE, EXTRACT and VERIFY runs only.
 * writeRenderSeconds: latest successful WRITE start to the end of the last successful RENDER after it.
 */
@Service
public class PaperMetricsService {

    public static final List<String> STAGES = List.of("PARSE", "EXTRACT", "VERIFY", "WRITE", "RENDER");

    private final PipelineRunRepository pipelineRunRepository;
    private final ClaimRepository claimRepository;
    private final HumanEventRepository humanEventRepository;

    public PaperMetricsService(PipelineRunRepository pipelineRunRepository,
                               ClaimRepository claimRepository,
                               HumanEventRepository humanEventRepository) {
        this.pipelineRunRepository = pipelineRunRepository;
        this.claimRepository = claimRepository;
        this.humanEventRepository = humanEventRepository;
    }

    private static String runningStage(PaperStatus status) {
        if (status == null) return null;
        return switch (status) {
            case PARSING -> "PARSE";
            case EXTRACTING -> "EXTRACT";
            case VERIFYING -> "VERIFY";
            case WRITING -> "WRITE";
            case RENDERING -> "RENDER";
            default -> null;
        };
    }

    private static Double seconds(Instant start, Instant end) {
        if (start == null || end == null) return null;
        return Math.round(Duration.between(start, end).toMillis() / 100.0) / 10.0;
    }

    /** Latest run of each stage, in pipeline order: [{stage, status, startedAt, endedAt, seconds, model, llmCalls, cacheHits, error}]. */
    public List<Map<String, Object>> stageRuns(String paperId, PaperStatus paperStatus) {
        Map<String, PipelineRunEntity> latest = new HashMap<>();
        for (PipelineRunEntity r : pipelineRunRepository.findByPaperIdOrderByStartedAtAsc(paperId)) {
            latest.put(r.getStage().toUpperCase(Locale.ROOT), r);
        }
        String running = runningStage(paperStatus);
        boolean afterRunning = false;

        List<Map<String, Object>> out = new ArrayList<>();
        for (String stage : STAGES) {
            PipelineRunEntity r = latest.get(stage);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stage", stage);
            String status;
            if (stage.equals(running)) {
                status = "RUNNING";
                r = null; // the in-flight run is only persisted when it ends
            } else if (afterRunning || r == null) {
                status = "WAITING";
                r = null;
            } else {
                status = Boolean.TRUE.equals(r.getOk()) ? "DONE" : "FAILED";
            }
            if (stage.equals(running)) afterRunning = true;
            m.put("status", status);
            m.put("startedAt", r != null ? r.getStartedAt() : null);
            m.put("endedAt", r != null ? r.getEndedAt() : null);
            m.put("seconds", r != null ? seconds(r.getStartedAt(), r.getEndedAt()) : null);
            m.put("model", r != null ? r.getModel() : null);
            m.put("llmCalls", r != null ? r.getLlmCalls() : null);
            m.put("cacheHits", r != null ? r.getCacheHits() : null);
            m.put("error", r != null ? r.getError() : null);
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> stats(String paperId) {
        List<ClaimEntity> claims = claimRepository.findByPaperIdOrderByIdAsc(paperId);
        int approved = 0, edited = 0, rejected = 0, pending = 0, needsEvidence = 0, flagged = 0, reviewSecs = 0;
        for (ClaimEntity c : claims) {
            DoctorStatus s = c.getDoctorStatus();
            if (s == DoctorStatus.APPROVED) approved++;
            else if (s == DoctorStatus.EDITED) edited++;
            else if (s == DoctorStatus.REJECTED) rejected++;
            else if (s == DoctorStatus.PENDING) pending++;
            else if (s == DoctorStatus.NEEDS_EVIDENCE) needsEvidence++;
            // Auto-flagged = what the pipeline set: AMBER/RED risk or NEEDS_EVIDENCE.
            if (c.getRisk() == Risk.AMBER || c.getRisk() == Risk.RED || s == DoctorStatus.NEEDS_EVIDENCE) flagged++;
            if (c.getReviewSeconds() != null) reviewSecs += c.getReviewSeconds();
        }
        int humanSeconds = humanEventRepository.findByPaperIdOrderByAtAsc(paperId).stream()
                .mapToInt(e -> e.getSeconds() != null ? e.getSeconds() : 0).sum();

        List<PipelineRunEntity> ok = pipelineRunRepository.findByPaperIdOrderByStartedAtAsc(paperId).stream()
                .filter(r -> Boolean.TRUE.equals(r.getOk()) && r.getStartedAt() != null && r.getEndedAt() != null)
                .toList();

        Double pipelineSeconds = null, pipelineActiveSeconds = null, writeRenderSeconds = null;
        PipelineRunEntity verify = lastOf(ok, "VERIFY", null);
        if (verify != null) {
            PipelineRunEntity parse = lastOf(ok, "PARSE", verify.getStartedAt());
            if (parse != null) {
                pipelineSeconds = seconds(parse.getStartedAt(), verify.getEndedAt());
                PipelineRunEntity extract = lastOf(ok, "EXTRACT", verify.getStartedAt());
                double active = seconds(parse.getStartedAt(), parse.getEndedAt()) + seconds(verify.getStartedAt(), verify.getEndedAt());
                if (extract != null && !extract.getStartedAt().isBefore(parse.getStartedAt())) {
                    active += seconds(extract.getStartedAt(), extract.getEndedAt());
                }
                pipelineActiveSeconds = Math.round(active * 10) / 10.0;
            }
        }
        PipelineRunEntity write = lastOf(ok, "WRITE", null);
        if (write != null) {
            PipelineRunEntity render = ok.stream()
                    .filter(r -> "RENDER".equalsIgnoreCase(r.getStage()) && !r.getStartedAt().isBefore(write.getStartedAt()))
                    .reduce((a, b) -> b).orElse(null);
            if (render != null) writeRenderSeconds = seconds(write.getStartedAt(), render.getEndedAt());
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("claimsTotal", claims.size());
        stats.put("claimsAutoFlagged", flagged);
        stats.put("approved", approved);
        stats.put("edited", edited);
        stats.put("rejected", rejected);
        stats.put("pending", pending);
        stats.put("needsEvidence", needsEvidence);
        stats.put("reviewSecondsTotal", reviewSecs);
        stats.put("humanSecondsTotal", humanSeconds);
        stats.put("pipelineSeconds", pipelineSeconds);
        stats.put("pipelineActiveSeconds", pipelineActiveSeconds);
        stats.put("writeRenderSeconds", writeRenderSeconds);
        return stats;
    }

    /** Last successful run of a stage, optionally only those started at or before {@code notAfter}. */
    private static PipelineRunEntity lastOf(List<PipelineRunEntity> ok, String stage, Instant notAfter) {
        PipelineRunEntity found = null;
        for (PipelineRunEntity r : ok) {
            if (stage.equalsIgnoreCase(r.getStage()) && (notAfter == null || !r.getStartedAt().isAfter(notAfter))) {
                found = r;
            }
        }
        return found;
    }
}
