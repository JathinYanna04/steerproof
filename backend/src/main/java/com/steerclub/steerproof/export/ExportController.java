package com.steerclub.steerproof.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steerclub.steerproof.asset.AssetEntity;
import com.steerclub.steerproof.asset.AssetRepository;
import com.steerclub.steerproof.audit.AuditService;
import com.steerclub.steerproof.claim.ClaimEntity;
import com.steerclub.steerproof.claim.ClaimRepository;
import com.steerclub.steerproof.common.ApiException;
import com.steerclub.steerproof.common.AssetStatus;
import com.steerclub.steerproof.common.DoctorStatus;
import com.steerclub.steerproof.common.PaperStatus;
import com.steerclub.steerproof.common.Severity;
import com.steerclub.steerproof.metric.HumanEventEntity;
import com.steerclub.steerproof.metric.HumanEventRepository;
import com.steerclub.steerproof.paper.PaperEntity;
import com.steerclub.steerproof.paper.PaperRepository;
import com.steerclub.steerproof.passage.PassageEntity;
import com.steerclub.steerproof.passage.PassageRepository;
import com.steerclub.steerproof.policy.PolicyViolationEntity;
import com.steerclub.steerproof.policy.PolicyViolationRepository;
import com.steerclub.steerproof.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api")
public class ExportController {

    private static final Logger log = LoggerFactory.getLogger(ExportController.class);

    private final PaperRepository paperRepository;
    private final AssetRepository assetRepository;
    private final ClaimRepository claimRepository;
    private final PassageRepository passageRepository;
    private final PolicyViolationRepository policyViolationRepository;
    private final HumanEventRepository humanEventRepository;
    private final AuditService auditService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    /** Where export regenerates the credits file. Blank = <STORAGE_DIR>/../demo/CREDITS.md; "none" disables it (tests). */
    @org.springframework.beans.factory.annotation.Value("${app.credits-file:}")
    private String creditsFile;

    public ExportController(
            PaperRepository paperRepository,
            AssetRepository assetRepository,
            ClaimRepository claimRepository,
            PassageRepository passageRepository,
            PolicyViolationRepository policyViolationRepository,
            HumanEventRepository humanEventRepository,
            AuditService auditService,
            StorageService storageService,
            ObjectMapper objectMapper
    ) {
        this.paperRepository = paperRepository;
        this.assetRepository = assetRepository;
        this.claimRepository = claimRepository;
        this.passageRepository = passageRepository;
        this.policyViolationRepository = policyViolationRepository;
        this.humanEventRepository = humanEventRepository;
        this.auditService = auditService;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/papers/{id}/export")
    @Transactional
    public ResponseEntity<?> exportPaper(@PathVariable("id") String paperId) {
        PaperEntity paper = paperRepository.findById(paperId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Paper not found: " + paperId));

        List<AssetEntity> assets = assetRepository.findByPaperIdOrderByIdAsc(paperId);
        if (assets.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "EXPORT_BLOCKED", "Paper has no assets generated");
        }

        List<Map<String, Object>> blockedBy = new ArrayList<>();
        for (AssetEntity a : assets) {
            if (a.getStatus() != AssetStatus.APPROVED) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("assetId", a.getId());
                item.put("type", a.getType().name());
                item.put("status", a.getStatus().name());
                item.put("reason", a.getStatus() == AssetStatus.RENDERED ? "RENDERED_NOT_APPROVED" : a.getStatus().name());
                blockedBy.add(item);
            }
        }

        if (!blockedBy.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "EXPORT_BLOCKED",
                    "message", "Paper cannot be exported because not all assets are approved",
                    "details", Map.of("blockedBy", blockedBy)
            ));
        }

        // Determine reviewer name and date from asset approvals
        String reviewerName = "Jathin Reddy Yanna (non-clinician reviewer)";
        for (AssetEntity a : assets) {
            if (a.getApprovedBy() != null && !a.getApprovedBy().isBlank()) {
                reviewerName = a.getApprovedBy();
                break;
            }
        }
        String approvalDate = LocalDate.now().toString();

        // Build ZIP
        Path exportZipPath = storageService.getRootPath().resolve("exports").resolve(paperId + ".zip");
        try {
            Files.createDirectories(exportZipPath.getParent());
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(exportZipPath.toFile()))) {
                // 1. Carousel PNGs
                Path carouselDir = storageService.getRootPath().resolve("renders").resolve(paperId).resolve("carousel");
                if (Files.exists(carouselDir) && Files.isDirectory(carouselDir)) {
                    try (var stream = Files.list(carouselDir)) {
                        for (Path p : stream.filter(f -> f.toString().endsWith(".png")).sorted().toList()) {
                            addFileToZip(zos, p, "carousel/" + p.getFileName().toString());
                        }
                    }
                }

                // 2. Static PNGs
                Path rendersDir = storageService.getRootPath().resolve("renders").resolve(paperId);
                if (Files.exists(rendersDir)) {
                    Path staticFile = rendersDir.resolve("static.png");
                    if (Files.exists(staticFile)) {
                        addFileToZip(zos, staticFile, "static.png");
                    }
                    Path staticSq = rendersDir.resolve("static-1080x1080.png");
                    if (Files.exists(staticSq)) {
                        addFileToZip(zos, staticSq, "static-1080x1080.png");
                    }
                    Path staticStory = rendersDir.resolve("static-1080x1920.png");
                    if (Files.exists(staticStory)) {
                        addFileToZip(zos, staticStory, "static-1080x1920.png");
                    }
                }

                // 3. Reel MP4
                Path reelFile = rendersDir.resolve("reel.mp4");
                if (Files.exists(reelFile)) {
                    addFileToZip(zos, reelFile, "reel.mp4");
                }

                // 4. Caption.txt
                Optional<AssetEntity> capOpt = assets.stream().filter(a -> a.getType().name().equals("CAPTION")).findFirst();
                StringBuilder captionSb = new StringBuilder();
                if (capOpt.isPresent()) {
                    try {
                        JsonNode capNode = objectMapper.readTree(capOpt.get().getContent());
                        JsonNode paras = capNode.has("paragraphs") ? capNode.get("paragraphs") : null;
                        if (paras != null && paras.isArray()) {
                            for (JsonNode p : paras) {
                                if (p.has("text")) {
                                    captionSb.append(p.get("text").asText()).append("\n\n");
                                }
                            }
                        }
                        JsonNode tags = capNode.has("hashtags") ? capNode.get("hashtags") : null;
                        if (tags != null && tags.isArray()) {
                            List<String> tagList = new ArrayList<>();
                            for (JsonNode t : tags) tagList.add(t.asText());
                            captionSb.append(String.join(" ", tagList)).append("\n");
                        }
                    } catch (Exception ignored) {}
                }
                addStringToZip(zos, captionSb.toString().trim() + "\n", "caption.txt");

                // 5. Sources.md
                List<ClaimEntity> claims = claimRepository.findByPaperIdOrderByIdAsc(paperId);
                StringBuilder sourcesSb = new StringBuilder();
                sourcesSb.append("# Scientific Sources & Evidence\n\n");
                sourcesSb.append("**Study**: ").append(paper.getTitle() != null ? paper.getTitle() : "Clinical Study").append("\n");
                sourcesSb.append("**Authors**: ").append(paper.getAuthors() != null ? paper.getAuthors() : "Authors").append("\n");
                sourcesSb.append("**Journal**: ").append(paper.getJournal() != null ? paper.getJournal() : "Journal").append(" (")
                        .append(paper.getYear() != null ? paper.getYear() : "Year").append(")\n\n");
                sourcesSb.append("## Claim Verification Table\n\n");
                sourcesSb.append("| Claim ID | On-Post Text | Exact Quote | Page | Section | Citation |\n");
                sourcesSb.append("| :--- | :--- | :--- | :--- | :--- | :--- |\n");

                for (ClaimEntity c : claims) {
                    if (c.getDoctorStatus() == DoctorStatus.APPROVED || c.getDoctorStatus() == DoctorStatus.EDITED) {
                        PassageEntity pass = c.getPassageId() != null ? passageRepository.findById(c.getPassageId()).orElse(null) : null;
                        String onPost = c.getEditedCopy() != null ? c.getEditedCopy() : (c.getConsumerDraft() != null ? c.getConsumerDraft() : "");
                        String quote = c.getQuote() != null ? c.getQuote().replace("\n", " ").replace("|", "\\|") : "";
                        String page = pass != null && pass.getPage() != null ? String.valueOf(pass.getPage()) : "N/A";
                        String sec = pass != null && pass.getSection() != null ? pass.getSection().replace("|", "\\|") : "N/A";
                        String cit = com.steerclub.steerproof.common.CitationHelper.formatShortCitation(paper.getAuthors(), paper.getYear(), paper.getJournal());
                        sourcesSb.append(String.format("| %s | %s | %s | %s | %s | %s |\n",
                                c.getId(),
                                onPost.replace("\n", " ").replace("|", "\\|"),
                                quote,
                                page,
                                sec,
                                cit
                        ));
                    }
                }

                sourcesSb.append("\n## Media & Asset Credits\n\n");
                sourcesSb.append("### Typography\n");
                sourcesSb.append("- **Instrument Serif** (Regular & Italic) – SIL Open Font License 1.1\n");
                sourcesSb.append("- **Inter** (Regular, Medium, SemiBold) – SIL Open Font License 1.1\n");
                sourcesSb.append("- **Caveat** (Medium) – SIL Open Font License 1.1\n\n");
                sourcesSb.append("### Audio & Music\n");
                sourcesSb.append("- **Track**: Calm Ambient (ID: 111899)\n");
                sourcesSb.append("- **Artist**: Lesfm / Pixabay\n");
                sourcesSb.append("- **Source**: [Pixabay Music](https://pixabay.com/music/ambient-calm-ambient-111899/)\n");
                sourcesSb.append("- **License**: Pixabay Content License (Free for commercial and non-commercial use)\n\n");
                sourcesSb.append("### Video Footage\n");
                Optional<AssetEntity> reelOpt = assets.stream().filter(a -> a.getType().name().equals("REEL")).findFirst();
                Set<String> reelClipsUsed = new LinkedHashSet<>();
                if (reelOpt.isPresent() && reelOpt.get().getContent() != null) {
                    try {
                        JsonNode reelNode = objectMapper.readTree(reelOpt.get().getContent());
                        JsonNode scenesNode = reelNode.has("scenes") ? reelNode.get("scenes") : null;
                        if (scenesNode != null && scenesNode.isArray()) {
                            for (JsonNode scene : scenesNode) {
                                String creditLine = scene.path("creditLine").asText("");
                                String videoPath = scene.path("videoPath").asText(scene.path("brollSrc").asText(""));
                                String creator = scene.path("creator").asText(scene.path("photographer").asText(""));
                                String url = scene.path("url").asText(scene.path("pexelsUrl").asText(""));
                                String source = scene.path("source").asText("Pixabay");

                                if (!creditLine.isBlank()) {
                                    reelClipsUsed.add(creditLine);
                                } else if (!creator.isBlank() && !creator.equalsIgnoreCase("null") && !creator.equalsIgnoreCase("TEST FIXTURE")) {
                                    String line = "Video by " + creator + (source.isBlank() ? "" : " on " + source) + (url.isBlank() ? "" : " — " + url);
                                    reelClipsUsed.add(line);
                                } else if (!videoPath.isBlank() && !videoPath.equalsIgnoreCase("null") && !videoPath.contains("fixture")) {
                                    reelClipsUsed.add("Video clip: " + videoPath);
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }

                if (!reelClipsUsed.isEmpty()) {
                    for (String clip : reelClipsUsed) {
                        sourcesSb.append("- ").append(clip).append("\n");
                    }
                } else {
                    sourcesSb.append("- Motion video footage: Steer procedural motion background (cream #FBF8F3 / blush #E6CBBD / maroon #3D141A).\n");
                }
                addStringToZip(zos, sourcesSb.toString(), "sources.md");

                // 6. Review_report.json
                Map<String, Object> reviewReport = new LinkedHashMap<>();
                reviewReport.put("paperId", paperId);
                reviewReport.put("exportedAt", Instant.now().toString());
                reviewReport.put("reviewer", reviewerName);

                List<Map<String, Object>> assetReports = new ArrayList<>();
                for (AssetEntity a : assets) {
                    Map<String, Object> ar = new LinkedHashMap<>();
                    ar.put("assetId", a.getId());
                    ar.put("type", a.getType().name());
                    ar.put("version", a.getVersion());
                    ar.put("status", a.getStatus().name());
                    ar.put("approvedBy", a.getApprovedBy());
                    ar.put("approvedAt", a.getApprovedAt() != null ? a.getApprovedAt().toString() : null);

                    List<PolicyViolationEntity> viols = policyViolationRepository.findByAssetId(a.getId());
                    List<String> overrides = new ArrayList<>();
                    for (PolicyViolationEntity v : viols) {
                        if (v.getSeverity() == Severity.WARNING) {
                            overrides.add(v.getRule() + ": " + v.getDetail());
                        }
                    }
                    ar.put("violationsOverridden", overrides);
                    assetReports.add(ar);
                }
                reviewReport.put("assets", assetReports);

                List<Map<String, Object>> claimReports = new ArrayList<>();
                for (ClaimEntity c : claims) {
                    Map<String, Object> cr = new LinkedHashMap<>();
                    cr.put("claimId", c.getId());
                    cr.put("status", c.getDoctorStatus().name());
                    cr.put("doctorName", c.getDoctorName());
                    cr.put("doctorNote", c.getDoctorNote());
                    cr.put("reviewedAt", c.getReviewedAt() != null ? c.getReviewedAt().toString() : null);
                    cr.put("reviewSeconds", c.getReviewSeconds());
                    claimReports.add(cr);
                }
                reviewReport.put("claims", claimReports);

                addStringToZip(zos, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(reviewReport), "review_report.json");

                // 7. README.txt
                String readme = "Approved for publishing by " + reviewerName + " on " + approvalDate + "\n" +
                        "Paper ID: " + paperId + "\n" +
                        "SteerProof Clinical Verification Suite\n" +
                        "https://steerclub.com\n";
                addStringToZip(zos, readme, "README.txt");
            }
        } catch (IOException e) {
            log.error("Failed to generate export zip for {}: {}", paperId, e.getMessage(), e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "EXPORT_FAILED", "Failed to build export archive: " + e.getMessage());
        }

        // Update paper status
        PaperStatus prev = paper.getStatus();
        paper.setStatus(PaperStatus.EXPORTED);
        paperRepository.save(paper);

        // Regenerate demo/CREDITS.md from clips used across all exported reels
        regenerateDemoCredits();

        // Audit & HumanEvent
        auditService.log(reviewerName, "PAPER_EXPORTED", "paper", paperId, Map.of("prevStatus", prev != null ? prev.name() : "null"), Map.of("status", "EXPORTED", "zipUrl", "/files/exports/" + paperId + ".zip"));
        humanEventRepository.save(new HumanEventEntity(paperId, reviewerName, "EXPORT", 0));

        // Calculate statistics
        List<ClaimEntity> allClaims = claimRepository.findByPaperIdOrderByIdAsc(paperId);
        long claimsApproved = allClaims.stream().filter(c -> c.getDoctorStatus() == DoctorStatus.APPROVED || c.getDoctorStatus() == DoctorStatus.EDITED).count();
        List<HumanEventEntity> humanEvents = humanEventRepository.findByPaperIdOrderByAtAsc(paperId);
        int totalHumanSeconds = humanEvents.stream().mapToInt(h -> h.getSeconds() != null ? h.getSeconds() : 0).sum();

        return ResponseEntity.ok(Map.of(
                "paperId", paperId,
                "status", "EXPORTED",
                "zipUrl", "/files/exports/" + paperId + ".zip",
                "stats", Map.of(
                        "claimsTotal", allClaims.size(),
                        "claimsApproved", claimsApproved,
                        "assetsApproved", assets.size(),
                        "totalHumanSeconds", totalHumanSeconds
                )
        ));
    }

    private void regenerateDemoCredits() {
        try {
            if ("none".equalsIgnoreCase(creditsFile)) {
                return;
            }
            Path creditsPath = (creditsFile == null || creditsFile.isBlank())
                    ? storageService.getRootPath().getParent().resolve("demo").resolve("CREDITS.md")
                    : Path.of(creditsFile);
            List<PaperEntity> exportedPapers = paperRepository.findAll().stream()
                    .filter(p -> p.getStatus() == PaperStatus.EXPORTED)
                    .toList();

            Set<String> allClipsUsed = new LinkedHashSet<>();
            for (PaperEntity p : exportedPapers) {
                List<AssetEntity> pAssets = assetRepository.findByPaperIdOrderByIdAsc(p.getId());
                for (AssetEntity a : pAssets) {
                    if (a.getType().name().equals("REEL") && a.getContent() != null) {
                        try {
                            JsonNode rNode = objectMapper.readTree(a.getContent());
                            JsonNode sNodes = rNode.has("scenes") ? rNode.get("scenes") : null;
                            if (sNodes != null && sNodes.isArray()) {
                                for (JsonNode scene : sNodes) {
                                    String cl = scene.path("creditLine").asText("");
                                    String creator = scene.path("creator").asText(scene.path("photographer").asText(""));
                                    String url = scene.path("url").asText(scene.path("pexelsUrl").asText(""));
                                    String source = scene.path("source").asText("Pixabay");
                                    if (!cl.isBlank()) {
                                        allClipsUsed.add(cl);
                                    } else if (!creator.isBlank() && !creator.equalsIgnoreCase("null") && !creator.equalsIgnoreCase("TEST FIXTURE")) {
                                        allClipsUsed.add("Video by " + creator + (source.isBlank() ? "" : " on " + source) + (url.isBlank() ? "" : " — " + url));
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("# Credits & Attributions\n\n");
            sb.append("## Audio & Music\n");
            sb.append("- **Track**: Calm Ambient (ID: 111899)\n");
            sb.append("- **Artist**: Lesfm / Pixabay\n");
            sb.append("- **Source**: [Pixabay Music](https://pixabay.com/music/ambient-calm-ambient-111899/)\n");
            sb.append("- **File**: `render-service/assets/music/calm-1.mp3`\n");
            sb.append("- **License**: Pixabay Content License (Free for commercial and non-commercial use, no attribution required)\n\n");
            sb.append("## Typography (Google Fonts)\n");
            sb.append("- **Instrument Serif** (Regular & Italic): Designed by Rodrigo Fuenzalida, Jordan Wilson (SIL Open Font License 1.1)\n");
            sb.append("- **Inter** (400 Regular, 500 Medium, 600 SemiBold): Designed by Rasmus Andersson (SIL Open Font License 1.1)\n");
            sb.append("- **Caveat** (500 Medium): Designed by Pablo Impallari (SIL Open Font License 1.1)\n\n");
            sb.append("## B-roll Video Footage\n");
            if (!allClipsUsed.isEmpty()) {
                for (String clip : allClipsUsed) {
                    sb.append("- ").append(clip).append("\n");
                }
            } else {
                sb.append("- No external video clips currently in use across exported reels. SteerProof renders procedural motion backgrounds using Steer brand palettes (`#FBF8F3` cream, `#E6CBBD` blush, `#3D141A` maroon).\n");
            }
            sb.append("\n");

            Files.writeString(creditsPath, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to regenerate demo/CREDITS.md: {}", e.getMessage());
        }
    }

    private void addFileToZip(ZipOutputStream zos, Path file, String zipPath) throws IOException {
        if (!Files.exists(file) || Files.isDirectory(file)) return;
        ZipEntry entry = new ZipEntry(zipPath);
        zos.putNextEntry(entry);
        Files.copy(file, zos);
        zos.closeEntry();
    }

    private void addStringToZip(ZipOutputStream zos, String content, String zipPath) throws IOException {
        ZipEntry entry = new ZipEntry(zipPath);
        zos.putNextEntry(entry);
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
