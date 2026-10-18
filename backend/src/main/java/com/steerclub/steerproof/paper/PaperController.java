package com.steerclub.steerproof.paper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steerclub.steerproof.claim.ClaimEntity;
import com.steerclub.steerproof.claim.ClaimRepository;
import com.steerclub.steerproof.common.ApiException;
import com.steerclub.steerproof.common.DoctorStatus;
import com.steerclub.steerproof.common.IdGenerator;
import com.steerclub.steerproof.common.PaperStatus;
import com.steerclub.steerproof.passage.PassageEntity;
import com.steerclub.steerproof.passage.PassageRepository;
import com.steerclub.steerproof.pipeline.PipelineRunEntity;
import com.steerclub.steerproof.pipeline.PipelineRunRepository;
import com.steerclub.steerproof.pipeline.PipelineService;
import com.steerclub.steerproof.storage.StorageService;
import com.steerclub.steerproof.metric.HumanEventEntity;
import com.steerclub.steerproof.metric.HumanEventRepository;
import com.steerclub.steerproof.metric.PaperMetricsService;
import com.steerclub.steerproof.review.ReviewService;
import com.steerclub.steerproof.common.Risk;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api")
public class PaperController {

    private final PaperRepository paperRepository;
    private final PassageRepository passageRepository;
    private final ClaimRepository claimRepository;
    private final PipelineRunRepository pipelineRunRepository;
    private final PipelineService pipelineService;
    private final StorageService storageService;
    private final IdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final ReviewService reviewService;
    private final HumanEventRepository humanEventRepository;
    private final PaperMetricsService metricsService;

    @Value("${app.demo-mode:true}")
    private boolean demoMode;

    public PaperController(
            PaperRepository paperRepository,
            PassageRepository passageRepository,
            ClaimRepository claimRepository,
            PipelineRunRepository pipelineRunRepository,
            PipelineService pipelineService,
            StorageService storageService,
            IdGenerator idGenerator,
            ObjectMapper objectMapper,
            ReviewService reviewService,
            HumanEventRepository humanEventRepository,
            PaperMetricsService metricsService
    ) {
        this.paperRepository = paperRepository;
        this.passageRepository = passageRepository;
        this.claimRepository = claimRepository;
        this.pipelineRunRepository = pipelineRunRepository;
        this.pipelineService = pipelineService;
        this.storageService = storageService;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.reviewService = reviewService;
        this.humanEventRepository = humanEventRepository;
        this.metricsService = metricsService;
    }

    @PostConstruct
    public void initSequence() {
        int maxPaper = 0;
        for (PaperEntity p : paperRepository.findAll()) {
            if (p.getId() != null && p.getId().startsWith("P-")) {
                try {
                    int num = Integer.parseInt(p.getId().substring(2));
                    if (num > maxPaper) maxPaper = num;
                } catch (NumberFormatException ignored) {}
            }
        }
        idGenerator.initSequenceFromMax(maxPaper, 0);
    }

    @PostMapping(value = "/papers", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadPaper(
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "url", required = false) String url,
            @RequestParam(value = "theme", required = false) String theme,
            @RequestParam(value = "batchId", required = false) String batchId
    ) throws IOException {
        if ((file == null || file.isEmpty()) && (url == null || url.isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Either file or url must be provided");
        }

        String paperId = idGenerator.nextPaperId();
        String pdfRelPath = null;

        if (file != null && !file.isEmpty()) {
            pdfRelPath = storageService.saveFile("papers", paperId + ".pdf", file.getInputStream());
        }

        PaperEntity paper = new PaperEntity();
        paper.setId(paperId);
        paper.setPdfPath(pdfRelPath != null ? pdfRelPath : "papers/" + paperId + ".pdf");
        paper.setSourceUrl(url);
        paper.setTheme(theme);
        paper.setBatchId(batchId);
        paper.setStatus(PaperStatus.PARSING);
        paperRepository.save(paper);

        pipelineService.runPipeline(paperId);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "paperId", paperId,
                "status", PaperStatus.PARSING.name()
        ));
    }

    @GetMapping("/papers")
    public List<Map<String, Object>> listPapers() {
        List<PaperEntity> papers = paperRepository.findAllByOrderByCreatedAtDesc();
        return papers.stream().map(p -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("paperId", p.getId());
            map.put("title", p.getTitle());
            map.put("authors", p.getAuthors());
            map.put("year", p.getYear());
            map.put("journal", p.getJournal());
            map.put("doi", p.getDoi());
            map.put("pdfPath", p.getPdfPath());
            map.put("status", p.getStatus().name());
            map.put("createdAt", p.getCreatedAt());
            map.put("stats", metricsService.stats(p.getId()));
            return map;
        }).toList();
    }

    @GetMapping("/papers/{id}")
    public Map<String, Object> getPaper(@PathVariable("id") String id) {
        PaperEntity paper = paperRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Paper not found: " + id));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("paperId", paper.getId());
        res.put("title", paper.getTitle());
        res.put("authors", paper.getAuthors());
        res.put("year", paper.getYear());
        res.put("journal", paper.getJournal());
        res.put("doi", paper.getDoi());
        res.put("status", paper.getStatus().name());
        res.put("failedStage", paper.getFailedStage());
        res.put("error", paper.getError());
        res.put("createdAt", paper.getCreatedAt());
        res.put("stages", metricsService.stageRuns(id, paper.getStatus()));
        res.put("stats", metricsService.stats(id));
        return res;
    }

    /** Latest run of each stage (PARSE, EXTRACT, VERIFY, WRITE, RENDER), in pipeline order. */
    @GetMapping("/papers/{id}/runs")
    public List<Map<String, Object>> getRuns(@PathVariable("id") String id) {
        PaperEntity paper = paperRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Paper not found: " + id));
        return metricsService.stageRuns(id, paper.getStatus());
    }

    @PostMapping("/papers/{id}/retry")
    public ResponseEntity<Void> retryPaper(@PathVariable("id") String id) {
        PaperEntity paper = paperRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Paper not found: " + id));

        if (paper.getStatus() != PaperStatus.FAILED) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_FAILED", "Paper is not in FAILED state");
        }

        String failedStage = paper.getFailedStage();
        if ("PARSE".equalsIgnoreCase(failedStage) || failedStage == null) {
            paper.setStatus(PaperStatus.PARSING);
        } else if ("EXTRACT".equalsIgnoreCase(failedStage)) {
            paper.setStatus(PaperStatus.EXTRACTING);
        } else if ("VERIFY".equalsIgnoreCase(failedStage)) {
            paper.setStatus(PaperStatus.VERIFYING);
        } else if ("WRITE".equalsIgnoreCase(failedStage)) {
            paper.setStatus(PaperStatus.WRITING);
        } else if ("RENDER".equalsIgnoreCase(failedStage)) {
            paper.setStatus(PaperStatus.RENDERING);
        } else {
            paper.setStatus(PaperStatus.PARSING);
        }

        paper.setFailedStage(null);
        paper.setError(null);
        paperRepository.save(paper);

        pipelineService.runPipeline(id);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/papers/{id}/pdf")
    public ResponseEntity<Resource> getPaperPdf(@PathVariable("id") String id) {
        PaperEntity paper = paperRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Paper not found: " + id));

        Resource resource = storageService.loadAsResource(paper.getPdfPath());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .body(resource);
    }

    @GetMapping("/papers/{id}/pages/{n}.png")
    public ResponseEntity<Resource> getPageImage(@PathVariable("id") String id, @PathVariable("n") int n) {
        String path = "pages/" + id + "/" + n + ".png";
        Resource resource = storageService.loadAsResource(path);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(resource);
    }

    @GetMapping("/papers/{id}/passages")
    public List<Map<String, Object>> getPassages(
            @PathVariable("id") String id,
            @RequestParam(value = "page", required = false) Integer page
    ) {
        List<PassageEntity> list = (page != null)
                ? passageRepository.findByPaperIdAndPageOrderByOrdAsc(id, page)
                : passageRepository.findByPaperIdOrderByOrdAsc(id);

        return list.stream().map(p -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("passageId", p.getId());
            map.put("section", p.getSection());
            map.put("kind", p.getKind());
            map.put("page", p.getPage());
            try {
                map.put("bbox", p.getBbox() != null ? objectMapper.readValue(p.getBbox(), Object.class) : null);
            } catch (Exception e) {
                map.put("bbox", null);
            }
            map.put("pageWidth", p.getPageWidth());
            map.put("pageHeight", p.getPageHeight());
            map.put("text", p.getText());
            return map;
        }).toList();
    }

    @PostMapping("/papers/{id}/generate")
    public ResponseEntity<?> generatePaperAssets(@PathVariable("id") String id) {
        PaperEntity paper = paperRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Paper not found: " + id));

        ReviewService.Gate1Result gate1 = reviewService.checkGate1(id);
        if (!gate1.ok()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("pending", gate1.pending());
            details.put("needsEvidence", gate1.needsEvidence());
            details.put("approvedCount", gate1.approvedCount());

            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "GATE1_BLOCKED");
            err.put("message", "Gate 1 blocked: all claims must be reviewed (none PENDING or NEEDS_EVIDENCE) and at least 3 claims must be APPROVED or EDITED.");
            err.put("details", details);

            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(err);
        }

        paper.setStatus(PaperStatus.WRITING);
        paperRepository.save(paper);
        pipelineService.runPipeline(id);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "paperId", paper.getId(),
                "status", PaperStatus.WRITING.name()
        ));
    }

    @PostMapping("/papers/{id}/reset")
    public ResponseEntity<?> resetPaper(
            @PathVariable("id") String id,
            @RequestParam(value = "keepClaims", defaultValue = "false") boolean keepClaims
    ) {
        if (!demoMode) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEMO_MODE_REQUIRED", "Reset is only allowed when DEMO_MODE=true");
        }
        paperRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Paper not found: " + id));

        pipelineService.resetPaper(id, keepClaims);

        PaperEntity updated = paperRepository.findById(id).orElseThrow();
        return ResponseEntity.ok(Map.of(
                "paperId", id,
                "status", updated.getStatus().name()
        ));
    }

    public ResponseEntity<?> resetPaper(String id) {
        return resetPaper(id, false);
    }

    @PostMapping("/papers/{id}/human-events")
    public ResponseEntity<Void> logHumanEvent(
            @PathVariable("id") String id,
            @RequestBody Map<String, Object> body
    ) {
        paperRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Paper not found: " + id));

        String actor = (String) body.getOrDefault("actor", "Reviewer");
        String kind = (String) body.getOrDefault("kind", "MANUAL");
        Number secondsNum = (Number) body.getOrDefault("seconds", 0);
        humanEventRepository.save(new HumanEventEntity(id, actor, kind, secondsNum.intValue()));
        return ResponseEntity.ok().build();
    }
}
