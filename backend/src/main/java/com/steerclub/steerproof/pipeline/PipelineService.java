package com.steerclub.steerproof.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steerclub.steerproof.audit.AuditService;
import com.steerclub.steerproof.claim.ClaimEntity;
import com.steerclub.steerproof.claim.ClaimRepository;
import com.steerclub.steerproof.common.*;
import com.steerclub.steerproof.paper.PaperEntity;
import com.steerclub.steerproof.paper.PaperRepository;
import com.steerclub.steerproof.passage.PassageEntity;
import com.steerclub.steerproof.passage.PassageRepository;
import com.steerclub.steerproof.asset.AssetEntity;
import com.steerclub.steerproof.asset.AssetRepository;
import com.steerclub.steerproof.asset.AssetClaimRepository;
import com.steerclub.steerproof.policy.PolicyViolationRepository;
import com.steerclub.steerproof.metric.HumanEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

import com.steerclub.steerproof.asset.AssetClaimEntity;
import com.steerclub.steerproof.policy.PolicyChecker;
import com.steerclub.steerproof.policy.PolicyViolationEntity;
import com.fasterxml.jackson.databind.JsonNode;

@Service
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    private final PaperRepository paperRepository;
    private final PassageRepository passageRepository;
    private final ClaimRepository claimRepository;
    private final PipelineRunRepository pipelineRunRepository;
    private final MlServiceClient mlServiceClient;
    private final IdGenerator idGenerator;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final AssetRepository assetRepository;
    private final AssetClaimRepository assetClaimRepository;
    private final PolicyViolationRepository policyViolationRepository;
    private final HumanEventRepository humanEventRepository;
    private final PolicyChecker policyChecker;
    private final RenderServiceClient renderServiceClient;

    public PipelineService(
            PaperRepository paperRepository,
            PassageRepository passageRepository,
            ClaimRepository claimRepository,
            PipelineRunRepository pipelineRunRepository,
            MlServiceClient mlServiceClient,
            IdGenerator idGenerator,
            AuditService auditService,
            ObjectMapper objectMapper,
            AssetRepository assetRepository,
            AssetClaimRepository assetClaimRepository,
            PolicyViolationRepository policyViolationRepository,
            HumanEventRepository humanEventRepository,
            PolicyChecker policyChecker,
            RenderServiceClient renderServiceClient
    ) {
        this.paperRepository = paperRepository;
        this.passageRepository = passageRepository;
        this.claimRepository = claimRepository;
        this.pipelineRunRepository = pipelineRunRepository;
        this.mlServiceClient = mlServiceClient;
        this.idGenerator = idGenerator;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.assetRepository = assetRepository;
        this.assetClaimRepository = assetClaimRepository;
        this.policyViolationRepository = policyViolationRepository;
        this.humanEventRepository = humanEventRepository;
        this.policyChecker = policyChecker;
        this.renderServiceClient = renderServiceClient;
    }

    @Async
    @Transactional
    public void runPipeline(String paperId) {
        Optional<PaperEntity> opt = paperRepository.findById(paperId);
        if (opt.isEmpty()) {
            log.error("Paper not found: {}", paperId);
            return;
        }

        PaperEntity paper = opt.get();
        String currentStage = "PARSE";
        Instant stageStart = Instant.now();

        try {
            // Stage 1: PARSE
            if (paper.getStatus() == PaperStatus.PARSING) {
                currentStage = "PARSE";
                stageStart = Instant.now();
                PipelineRunEntity run = new PipelineRunEntity(paperId, "PARSE", stageStart);

                // If URL was given and pdfPath is missing, fetch first
                if ((paper.getPdfPath() == null || paper.getPdfPath().isBlank()) && paper.getSourceUrl() != null) {
                    MlServiceClient.FetchUrlResponse fetchRes = mlServiceClient.fetchUrl(paperId, paper.getSourceUrl());
                    paper.setPdfPath(fetchRes.pdfPath());
                }

                MlServiceClient.ParseResponse parseRes = mlServiceClient.parse(paperId, paper.getPdfPath());

                if (parseRes.meta() != null) {
                    if (parseRes.meta().title() != null) paper.setTitle(parseRes.meta().title());
                    if (parseRes.meta().authors() != null) paper.setAuthors(parseRes.meta().authors());
                    if (parseRes.meta().year() != null) paper.setYear(parseRes.meta().year());
                    if (parseRes.meta().journal() != null) paper.setJournal(parseRes.meta().journal());
                    if (parseRes.meta().doi() != null) paper.setDoi(parseRes.meta().doi());
                }
                paper.setPageCount(parseRes.pageCount());

                // Save passages
                List<PassageEntity> passageEntities = new ArrayList<>();
                for (MlServiceClient.PassageDto p : parseRes.passages()) {
                    String bboxJson = p.bbox() != null ? objectMapper.writeValueAsString(p.bbox()) : null;
                    Float pw = p.page_width() != null ? p.page_width().floatValue() : null;
                    Float ph = p.page_height() != null ? p.page_height().floatValue() : null;
                    passageEntities.add(new PassageEntity(
                            p.id(),
                            paperId,
                            p.section(),
                            p.kind(),
                            p.page(),
                            bboxJson,
                            pw,
                            ph,
                            p.text(),
                            p.ord()
                    ));
                }
                passageRepository.saveAll(passageEntities);

                run.setEndedAt(Instant.now());
                run.setOk(true);
                run.setLlmCalls(0);
                run.setCacheHits(0);
                pipelineRunRepository.save(run);

                PaperStatus prevStatus = paper.getStatus();
                paper.setStatus(PaperStatus.EXTRACTING);
                paperRepository.save(paper);
                auditService.log("system", "STATUS_CHANGE", "paper", paperId, prevStatus, PaperStatus.EXTRACTING);
            }

            // Stage 2: EXTRACT
            List<MlServiceClient.ExtractedClaimDto> extractedClaims = new ArrayList<>();
            if (paper.getStatus() == PaperStatus.EXTRACTING) {
                // Stable claim IDs rule: once any claim of a paper has been reviewed, extraction must never re-run for it.
                List<ClaimEntity> existingClaims = claimRepository.findByPaperIdOrderByIdAsc(paperId);
                boolean anyReviewed = existingClaims.stream().anyMatch(c -> c.getDoctorStatus() != DoctorStatus.PENDING || c.getReviewedAt() != null);
                if (anyReviewed) {
                    log.warn("Paper {} has reviewed claims; skipping re-extraction to preserve stable claim IDs.", paperId);
                    paper.setStatus(PaperStatus.AWAITING_CLAIM_REVIEW);
                    paperRepository.save(paper);
                    return;
                }

                currentStage = "EXTRACT";
                stageStart = Instant.now();
                PipelineRunEntity run = new PipelineRunEntity(paperId, "EXTRACT", stageStart);

                List<PassageEntity> passages = passageRepository.findByPaperIdOrderByOrdAsc(paperId);
                List<MlServiceClient.PassageDto> passageDtos = passages.stream().map(this::toPassageDto).toList();

                MlServiceClient.PaperMetaDto metaDto = new MlServiceClient.PaperMetaDto(
                        paper.getTitle(), paper.getAuthors(), paper.getYear(), paper.getJournal(), paper.getDoi()
                );

                MlServiceClient.ExtractResponse extractRes = mlServiceClient.extract(
                        paperId, metaDto, passageDtos, paper.getTheme()
                );
                extractedClaims = extractRes.claims();

                run.setEndedAt(Instant.now());
                run.setOk(true);
                run.setLlmCalls(extractRes.llmCalls());
                run.setCacheHits(extractRes.cacheHits());
                pipelineRunRepository.save(run);

                PaperStatus prevStatus = paper.getStatus();
                paper.setStatus(PaperStatus.VERIFYING);
                paperRepository.save(paper);
                auditService.log("system", "STATUS_CHANGE", "paper", paperId, prevStatus, PaperStatus.VERIFYING);
            }

            // Stage 3: VERIFY
            if (paper.getStatus() == PaperStatus.VERIFYING) {
                currentStage = "VERIFY";
                stageStart = Instant.now();
                PipelineRunEntity run = new PipelineRunEntity(paperId, "VERIFY", stageStart);

                List<PassageEntity> passages = passageRepository.findByPaperIdOrderByOrdAsc(paperId);
                List<MlServiceClient.PassageDto> passageDtos = passages.stream().map(this::toPassageDto).toList();

                List<MlServiceClient.VerifyClaimDto> verifyInput;
                if (!extractedClaims.isEmpty()) {
                    verifyInput = extractedClaims.stream().map(c ->
                            new MlServiceClient.VerifyClaimDto(
                                    c.passageId(),
                                    c.quote(),
                                    c.scientificClaim(),
                                    c.consumerDraft(),
                                    c.numbers(),
                                    c.studyDesign(),
                                    c.population(),
                                    c.findingClass(),
                                    c.limitations()
                            )
                    ).toList();
                } else {
                    List<ClaimEntity> existingClaims = claimRepository.findByPaperIdOrderByIdAsc(paperId);
                    if (!existingClaims.isEmpty()) {
                        verifyInput = existingClaims.stream().map(c ->
                            new MlServiceClient.VerifyClaimDto(
                                    c.getPassageId(),
                                    c.getQuote(),
                                    c.getScientificClaim(),
                                    c.getConsumerDraft(),
                                    parseNumbers(c.getNumbers()),
                                    c.getStudyDesign(),
                                    c.getPopulation(),
                                    c.getFindingClass() != null ? c.getFindingClass().name() : null,
                                    parseLimitations(c.getLimitations())
                            )
                        ).toList();
                    } else {
                        MlServiceClient.PaperMetaDto metaDto = new MlServiceClient.PaperMetaDto(
                                paper.getTitle(), paper.getAuthors(), paper.getYear(), paper.getJournal(), paper.getDoi()
                        );
                        MlServiceClient.ExtractResponse extractRes = mlServiceClient.extract(
                                paperId, metaDto, passageDtos, paper.getTheme()
                        );
                        extractedClaims = extractRes.claims();
                        verifyInput = extractedClaims.stream().map(c ->
                                new MlServiceClient.VerifyClaimDto(
                                        c.passageId(),
                                        c.quote(),
                                        c.scientificClaim(),
                                        c.consumerDraft(),
                                        c.numbers(),
                                        c.studyDesign(),
                                        c.population(),
                                        c.findingClass(),
                                        c.limitations()
                                )
                        ).toList();
                    }
                }

                MlServiceClient.VerifyResponse verifyRes = mlServiceClient.verify(paperId, verifyInput, passageDtos);

                Map<Integer, MlServiceClient.VerifyItemDetailDto> detailMap = new HashMap<>();
                for (MlServiceClient.VerifyItemDetailDto d : verifyRes.results()) {
                    detailMap.put(d.index(), d);
                }

                // Load existing claims to prevent duplicates and preserve reviewed status
                Map<String, ClaimEntity> existingByClaimId = new HashMap<>();
                for (ClaimEntity ec : claimRepository.findByPaperIdOrderByIdAsc(paperId)) {
                    existingByClaimId.put(ec.getId(), ec);
                }

                List<ClaimEntity> newClaims = new ArrayList<>();
                Set<String> processedIds = new HashSet<>();

                for (int i = 0; i < verifyInput.size(); i++) {
                    MlServiceClient.VerifyClaimDto raw = verifyInput.get(i);
                    MlServiceClient.VerifyItemDetailDto detail = detailMap.get(i);

                    String claimId = idGenerator.claimId(paperId, i + 1);
                    processedIds.add(claimId);
                    ClaimEntity claim = existingByClaimId.getOrDefault(claimId, new ClaimEntity());

                    claim.setId(claimId);
                    claim.setPaperId(paperId);
                    claim.setPassageId(detail != null && detail.passageId() != null ? detail.passageId() : raw.passageId());
                    claim.setQuote(raw.quote());
                    claim.setScientificClaim(raw.scientificClaim());
                    claim.setConsumerDraft(raw.consumerDraft());
                    claim.setNumbers(objectMapper.writeValueAsString(raw.numbers() != null ? raw.numbers() : List.of()));
                    claim.setStudyDesign(raw.studyDesign());
                    claim.setPopulation(raw.population());
                    
                    FindingClass fc = FindingClass.PRIMARY;
                    try {
                        if (raw.findingClass() != null) {
                            fc = FindingClass.valueOf(raw.findingClass().toUpperCase());
                        }
                    } catch (Exception ignored) {}
                    claim.setFindingClass(fc);
                    claim.setLimitations(objectMapper.writeValueAsString(raw.limitations() != null ? raw.limitations() : List.of()));

                    if (detail != null) {
                        claim.setQuoteScore(detail.quoteScore());
                        claim.setNumbersOk(detail.numbersOk());

                        try {
                            if (detail.entailment() != null) {
                                claim.setEntailment(Entailment.valueOf(detail.entailment().toUpperCase()));
                            }
                        } catch (Exception ignored) {}

                        Risk finalRisk = Risk.GREEN;
                        try {
                            if (detail.risk() != null) {
                                finalRisk = Risk.valueOf(detail.risk().toUpperCase());
                            }
                        } catch (Exception ignored) {}

                        // Risk Floor:
                        // 1. UNSUPPORTED -> RED
                        // 2. OVERCLAIM -> at least AMBER
                        // 3. numbersOk=false -> at least AMBER
                        if (claim.getEntailment() == Entailment.UNSUPPORTED) {
                            finalRisk = Risk.RED;
                        } else if (claim.getEntailment() == Entailment.OVERCLAIM && finalRisk == Risk.GREEN) {
                            finalRisk = Risk.AMBER;
                        }
                        if (Boolean.FALSE.equals(claim.getNumbersOk()) && finalRisk == Risk.GREEN) {
                            finalRisk = Risk.AMBER;
                        }

                        claim.setRisk(finalRisk);
                        claim.setRiskReason(detail.riskReason());

                        try {
                            if (detail.populationFit() != null) {
                                claim.setPopulationFit(PopulationFit.valueOf(detail.populationFit().toUpperCase()));
                            }
                        } catch (Exception ignored) {}
                        claim.setPopulationReason(detail.populationReason());

                        // Preserve reviewed doctor_status!
                        boolean alreadyReviewed = claim.getDoctorStatus() == DoctorStatus.APPROVED
                                || claim.getDoctorStatus() == DoctorStatus.EDITED
                                || claim.getDoctorStatus() == DoctorStatus.REJECTED;

                        if (!alreadyReviewed) {
                            if (detail.quoteScore() < 90) {
                                claim.setDoctorStatus(DoctorStatus.NEEDS_EVIDENCE);
                            } else {
                                claim.setDoctorStatus(DoctorStatus.PENDING);
                            }
                        }
                    } else if (claim.getDoctorStatus() == null) {
                        claim.setDoctorStatus(DoctorStatus.PENDING);
                    }

                    newClaims.add(claim);
                }

                // Delete any orphaned claims beyond the new count
                for (ClaimEntity oldClaim : existingByClaimId.values()) {
                    if (!processedIds.contains(oldClaim.getId())) {
                        claimRepository.delete(oldClaim);
                    }
                }

                claimRepository.saveAll(newClaims);

                run.setEndedAt(Instant.now());
                run.setOk(true);
                run.setLlmCalls(verifyRes.llmCalls());
                run.setCacheHits(verifyRes.cacheHits());
                pipelineRunRepository.save(run);

                PaperStatus prevStatus = paper.getStatus();
                paper.setStatus(PaperStatus.AWAITING_CLAIM_REVIEW);
                paper.setFailedStage(null);
                paper.setError(null);
                paperRepository.save(paper);
                auditService.log("system", "STATUS_CHANGE", "paper", paperId, prevStatus, PaperStatus.AWAITING_CLAIM_REVIEW);
            }

            // Stage 4: WRITE + POLICY
            if (paper.getStatus() == PaperStatus.WRITING) {
                currentStage = "WRITE";
                stageStart = Instant.now();
                PipelineRunEntity run = new PipelineRunEntity(paperId, "WRITE", stageStart);

                List<ClaimEntity> allClaims = claimRepository.findByPaperIdOrderByIdAsc(paperId);
                Map<String, ClaimEntity> claimsById = new HashMap<>();
                List<MlServiceClient.ApprovedClaimDto> approvedClaims = new ArrayList<>();
                for (ClaimEntity c : allClaims) {
                    claimsById.put(c.getId(), c);
                    if (c.getDoctorStatus() == DoctorStatus.APPROVED || c.getDoctorStatus() == DoctorStatus.EDITED) {
                        approvedClaims.add(new MlServiceClient.ApprovedClaimDto(
                                c.getId(),
                                c.getEditedCopy(),
                                c.getScientificClaim(),
                                c.getConsumerDraft(),
                                c.getQuote(),
                                c.getFindingClass() != null ? c.getFindingClass().name() : "PRIMARY",
                                parseNumbers(c.getNumbers()),
                                parseLimitations(c.getLimitations()),
                                c.getPopulation()
                        ));
                    }
                }

                MlServiceClient.PaperMetaDto metaDto = new MlServiceClient.PaperMetaDto(
                        paper.getTitle(), paper.getAuthors(), paper.getYear(), paper.getJournal(), paper.getDoi()
                );

                MlServiceClient.WriteResponse writeRes = mlServiceClient.write(
                        paperId, metaDto, approvedClaims, paper.getTheme(), "en", null
                );

                saveAndCheckAsset(paperId, AssetType.CAROUSEL, writeRes.carousel(), claimsById);
                saveAndCheckAsset(paperId, AssetType.STATIC, writeRes.staticBlock(), claimsById);
                saveAndCheckAsset(paperId, AssetType.REEL, writeRes.reel(), claimsById);
                saveAndCheckAsset(paperId, AssetType.CAPTION, writeRes.caption(), claimsById);

                run.setEndedAt(Instant.now());
                run.setOk(true);
                run.setLlmCalls(writeRes.llmCalls());
                run.setCacheHits(writeRes.cacheHits());
                pipelineRunRepository.save(run);

                PaperStatus prevStatus = paper.getStatus();
                paper.setStatus(PaperStatus.RENDERING);
                paper.setFailedStage(null);
                paper.setError(null);
                paperRepository.save(paper);
                auditService.log("system", "STATUS_CHANGE", "paper", paperId, prevStatus, PaperStatus.RENDERING);
            }

            // Stage 5: RENDER
            if (paper.getStatus() == PaperStatus.RENDERING) {
                currentStage = "RENDER";
                stageStart = Instant.now();
                PipelineRunEntity run = new PipelineRunEntity(paperId, "RENDER", stageStart);

                String citation = com.steerclub.steerproof.common.CitationHelper.formatShortCitation(paper.getAuthors(), paper.getYear(), paper.getJournal());

                // 1. Render Carousel
                String carId = idGenerator.assetId(paperId, AssetType.CAROUSEL);
                Optional<AssetEntity> carOpt = assetRepository.findById(carId);
                if (carOpt.isPresent()) {
                    AssetEntity carAsset = carOpt.get();
                    JsonNode carContent = objectMapper.readTree(carAsset.getContent());
                    JsonNode slidesNode = carContent.has("slides") ? carContent.get("slides") : carContent;
                    if (slidesNode != null && slidesNode.isArray()) {
                        List<Map<String, Object>> slidesList = objectMapper.convertValue(slidesNode, new TypeReference<List<Map<String, Object>>>() {});
                        boolean isDraft = carAsset.getStatus() != AssetStatus.APPROVED;
                        try {
                            RenderServiceClient.RenderResult res = renderServiceClient.renderCarousel(paperId, slidesList, citation, isDraft);
                            carAsset.setRenderPaths(objectMapper.writeValueAsString(res.paths()));

                            Map<String, Object> blockMap = new HashMap<>();
                            for (Map<String, Object> s : slidesList) {
                                int n = s.containsKey("n") ? ((Number) s.get("n")).intValue() : 1;
                                String kind = (String) s.getOrDefault("kind", "brand");
                                Object claimIds = s.getOrDefault("claimIds", List.of());
                                Map<String, Object> meta = Map.of("kind", kind, "claimIds", claimIds);
                                blockMap.put("slide" + n + ".headline", meta);
                                blockMap.put("slide" + n + ".body", meta);
                                blockMap.put("slide" + n + ".stat", meta);
                            }

                            if (carContent.isObject()) {
                                ((ObjectNode) carContent).set("blocks", objectMapper.valueToTree(res.blocks()));
                                ((ObjectNode) carContent).set("blockMap", objectMapper.valueToTree(blockMap));
                                carAsset.setContent(objectMapper.writeValueAsString(carContent));
                            }

                            if (carAsset.getStatus() != AssetStatus.BLOCKED && carAsset.getStatus() != AssetStatus.APPROVED) {
                                carAsset.setStatus(AssetStatus.RENDERED);
                            }
                        } catch (Exception ex) {
                            if (ex.getMessage() != null && ex.getMessage().contains("TEXT_OVERFLOW")) {
                                log.error("Carousel render overflow for {}: {}", paperId, ex.getMessage());
                                carAsset.setStatus(AssetStatus.BLOCKED);
                            } else {
                                throw ex;
                            }
                        }
                        assetRepository.save(carAsset);
                    }
                }

                // 2. Render Static
                String staId = idGenerator.assetId(paperId, AssetType.STATIC);
                Optional<AssetEntity> staOpt = assetRepository.findById(staId);
                if (staOpt.isPresent()) {
                    AssetEntity staAsset = staOpt.get();
                    JsonNode staContent = objectMapper.readTree(staAsset.getContent());
                    Map<String, Object> staContentMap = objectMapper.convertValue(staContent, new TypeReference<Map<String, Object>>() {});
                    boolean isDraft = staAsset.getStatus() != AssetStatus.APPROVED;
                    try {
                        RenderServiceClient.RenderResult res = renderServiceClient.renderStatic(paperId, staContentMap, citation, isDraft);
                        staAsset.setRenderPaths(objectMapper.writeValueAsString(res.paths()));

                        String kind = (String) staContentMap.getOrDefault("kind", "claim");
                        Object claimIds = staContentMap.getOrDefault("claimIds", List.of());
                        Map<String, Object> meta = Map.of("kind", kind, "claimIds", claimIds);
                        Map<String, Object> blockMap = Map.of(
                                "static.headline", meta,
                                "static.support", meta,
                                "static.footnote", meta
                        );

                        if (staContent.isObject()) {
                            ((ObjectNode) staContent).set("blocks", objectMapper.valueToTree(res.blocks()));
                            ((ObjectNode) staContent).set("blockMap", objectMapper.valueToTree(blockMap));
                            staAsset.setContent(objectMapper.writeValueAsString(staContent));
                        }

                        if (staAsset.getStatus() != AssetStatus.BLOCKED && staAsset.getStatus() != AssetStatus.APPROVED) {
                            staAsset.setStatus(AssetStatus.RENDERED);
                        }
                    } catch (Exception ex) {
                        if (ex.getMessage() != null && ex.getMessage().contains("TEXT_OVERFLOW")) {
                            log.error("Static render overflow for {}: {}", paperId, ex.getMessage());
                            staAsset.setStatus(AssetStatus.BLOCKED);
                        } else {
                            throw ex;
                        }
                    }
                    assetRepository.save(staAsset);
                }

                run.setEndedAt(Instant.now());
                run.setOk(true);
                run.setLlmCalls(0);
                run.setCacheHits(0);
                pipelineRunRepository.save(run);

                PaperStatus prevStatus = paper.getStatus();
                paper.setStatus(PaperStatus.AWAITING_ASSET_REVIEW);
                paper.setFailedStage(null);
                paper.setError(null);
                paperRepository.save(paper);
                auditService.log("system", "STATUS_CHANGE", "paper", paperId, prevStatus, PaperStatus.AWAITING_ASSET_REVIEW);
            }

        } catch (Exception e) {
            log.error("Pipeline failed for paper {} at stage {}: {}", paperId, currentStage, e.getMessage(), e);

            PipelineRunEntity failedRun = new PipelineRunEntity(paperId, currentStage, stageStart);
            failedRun.setEndedAt(Instant.now());
            failedRun.setOk(false);
            failedRun.setError(e.getMessage());
            pipelineRunRepository.save(failedRun);

            paper.setStatus(PaperStatus.FAILED);
            paper.setFailedStage(currentStage);
            paper.setError(e.getMessage());
            paperRepository.save(paper);
            auditService.log("system", "STATUS_CHANGE", "paper", paperId, null, PaperStatus.FAILED);
        }
    }

    private MlServiceClient.PassageDto toPassageDto(PassageEntity p) {
        Map<String, Double> bboxMap = null;
        if (p.getBbox() != null) {
            try {
                bboxMap = objectMapper.readValue(p.getBbox(), new TypeReference<Map<String, Double>>() {});
            } catch (Exception ignored) {}
        }
        return new MlServiceClient.PassageDto(
                p.getId(),
                p.getSection(),
                p.getKind(),
                p.getPage(),
                bboxMap,
                p.getPageWidth() != null ? p.getPageWidth().doubleValue() : null,
                p.getPageHeight() != null ? p.getPageHeight().doubleValue() : null,
                p.getText(),
                p.getOrd()
        );
    }

    private List<MlServiceClient.NumberRefDto> parseNumbers(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<MlServiceClient.NumberRefDto>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> parseLimitations(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    @Transactional
    public void resetPaper(String paperId) {
        resetPaper(paperId, false);
    }

    public void resetPaper(String paperId, boolean keepClaims) {
        PaperEntity paper = paperRepository.findById(paperId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Paper not found: " + paperId));

        if (!keepClaims) {
            List<AssetEntity> assets = assetRepository.findByPaperIdOrderByIdAsc(paperId);
            for (AssetEntity a : assets) {
                policyViolationRepository.deleteByAssetId(a.getId());
                assetClaimRepository.deleteByAssetId(a.getId());
            }
            assetRepository.deleteAll(assets);

            claimRepository.deleteAll(claimRepository.findByPaperIdOrderByIdAsc(paperId));
            humanEventRepository.deleteAll(humanEventRepository.findByPaperIdOrderByAtAsc(paperId));

            List<PipelineRunEntity> runs = pipelineRunRepository.findByPaperIdOrderByStartedAtAsc(paperId);
            for (PipelineRunEntity r : runs) {
                if (!"PARSE".equalsIgnoreCase(r.getStage())) {
                    pipelineRunRepository.delete(r);
                }
            }

            List<PassageEntity> passages = passageRepository.findByPaperIdOrderByOrdAsc(paperId);
            if (!passages.isEmpty()) {
                paper.setStatus(PaperStatus.EXTRACTING);
            } else {
                paper.setStatus(PaperStatus.PARSING);
            }
        } else {
            // Keep extracted claims, but reset all human reviews
            List<ClaimEntity> claims = claimRepository.findByPaperIdOrderByIdAsc(paperId);
            for (ClaimEntity c : claims) {
                if (c.getDoctorStatus() != DoctorStatus.NEEDS_EVIDENCE) {
                    c.setDoctorStatus(DoctorStatus.PENDING);
                }
                c.setDoctorName(null);
                c.setDoctorNote(null);
                c.setEditedCopy(null);
                c.setReviewSeconds(0);
                c.setReviewedAt(null);
            }
            claimRepository.saveAll(claims);

            // Reset asset review fields
            List<AssetEntity> assets = assetRepository.findByPaperIdOrderByIdAsc(paperId);
            for (AssetEntity a : assets) {
                if (a.getStatus() == AssetStatus.APPROVED) {
                    a.setStatus(AssetStatus.RENDERED);
                }
                a.setApprovedBy(null);
                a.setApprovedAt(null);
            }
            assetRepository.saveAll(assets);

            // Delete human events
            humanEventRepository.deleteAll(humanEventRepository.findByPaperIdOrderByAtAsc(paperId));

            paper.setStatus(PaperStatus.AWAITING_CLAIM_REVIEW);
        }

        paper.setFailedStage(null);
        paper.setError(null);
        paperRepository.save(paper);

        auditService.log("system", "PAPER_RESET", "paper", paperId, Map.of("keepClaims", keepClaims), Map.of("status", paper.getStatus().name()));
    }

    @Transactional
    public AssetEntity saveAndCheckAsset(
            String paperId,
            AssetType type,
            Map<String, Object> contentMap,
            Map<String, ClaimEntity> claimsById
    ) throws Exception {
        if (contentMap == null) return null;

        String assetId = idGenerator.assetId(paperId, type);
        JsonNode contentNode = objectMapper.valueToTree(contentMap);
        String contentJson = objectMapper.writeValueAsString(contentMap);

        // Delete old claims and violations for this asset
        assetClaimRepository.deleteByAssetId(assetId);
        policyViolationRepository.deleteByAssetId(assetId);

        // 1. Extract AssetClaims
        List<AssetClaimEntity> assetClaims = new ArrayList<>();
        if (type == AssetType.CAROUSEL) {
            JsonNode slides = contentNode.has("slides") ? contentNode.get("slides") : contentNode;
            if (slides != null && slides.isArray()) {
                for (int i = 0; i < slides.size(); i++) {
                    JsonNode slide = slides.get(i);
                    int n = slide.has("n") ? slide.get("n").asInt() : (i + 1);
                    String loc = "slide:" + n;
                    if (slide.has("claimIds") && slide.get("claimIds").isArray()) {
                        for (JsonNode cNode : slide.get("claimIds")) {
                            assetClaims.add(new AssetClaimEntity(assetId, cNode.asText(), loc));
                        }
                    }
                }
            }
        } else if (type == AssetType.STATIC) {
            if (contentNode.has("claimIds") && contentNode.get("claimIds").isArray()) {
                for (JsonNode cNode : contentNode.get("claimIds")) {
                    assetClaims.add(new AssetClaimEntity(assetId, cNode.asText(), "static:headline"));
                }
            }
        } else if (type == AssetType.REEL) {
            JsonNode scenes = contentNode.has("scenes") ? contentNode.get("scenes") : contentNode;
            if (scenes != null && scenes.isArray()) {
                for (int i = 0; i < scenes.size(); i++) {
                    JsonNode scene = scenes.get(i);
                    int n = scene.has("n") ? scene.get("n").asInt() : (i + 1);
                    String loc = "scene:" + n;
                    if (scene.has("claimIds") && scene.get("claimIds").isArray()) {
                        for (JsonNode cNode : scene.get("claimIds")) {
                            assetClaims.add(new AssetClaimEntity(assetId, cNode.asText(), loc));
                        }
                    }
                }
            }
        } else if (type == AssetType.CAPTION) {
            JsonNode paras = contentNode.has("paragraphs") ? contentNode.get("paragraphs") : null;
            if (paras != null && paras.isArray()) {
                for (int i = 0; i < paras.size(); i++) {
                    JsonNode p = paras.get(i);
                    String loc = "caption:p" + (i + 1);
                    if (p.has("claimIds") && p.get("claimIds").isArray()) {
                        for (JsonNode cNode : p.get("claimIds")) {
                            assetClaims.add(new AssetClaimEntity(assetId, cNode.asText(), loc));
                        }
                    }
                }
            }
        }

        // 2. Run PolicyChecker
        List<PolicyChecker.ViolationResult> violations = policyChecker.checkAsset(assetId, type.name(), contentNode, claimsById);
        boolean hasBlocking = false;
        List<PolicyViolationEntity> violationEntities = new ArrayList<>();
        for (PolicyChecker.ViolationResult v : violations) {
            violationEntities.add(new PolicyViolationEntity(assetId, v.claimId(), v.location(), v.rule(), v.severity(), v.detail()));
            if (v.severity() == Severity.BLOCKING) {
                hasBlocking = true;
            }
        }

        // 3. Save or update AssetEntity FIRST so foreign keys can reference assetId
        Optional<AssetEntity> existing = assetRepository.findById(assetId);
        AssetEntity asset = existing.orElseGet(() -> {
            AssetEntity a = new AssetEntity();
            a.setId(assetId);
            a.setPaperId(paperId);
            a.setType(type);
            a.setLanguage("en");
            a.setVersion(1);
            return a;
        });

        if (existing.isPresent()) {
            asset.setVersion(asset.getVersion() + 1);
        }
        asset.setContent(contentJson);
        asset.setStatus(hasBlocking ? AssetStatus.BLOCKED : AssetStatus.DRAFT);
        AssetEntity saved = assetRepository.save(asset);

        // 4. Save AssetClaims and PolicyViolations
        assetClaimRepository.saveAll(assetClaims);
        policyViolationRepository.saveAll(violationEntities);

        return saved;
    }

    public void regenerateSingleAsset(AssetEntity asset) {
        String paperId = asset.getPaperId();
        PaperEntity paper = paperRepository.findById(paperId).orElse(null);
        if (paper == null) return;

        List<ClaimEntity> allClaims = claimRepository.findByPaperIdOrderByIdAsc(paperId);
        Map<String, ClaimEntity> claimsById = new HashMap<>();
        List<MlServiceClient.ApprovedClaimDto> approvedClaims = new ArrayList<>();
        for (ClaimEntity c : allClaims) {
            claimsById.put(c.getId(), c);
            if (c.getDoctorStatus() == DoctorStatus.APPROVED || c.getDoctorStatus() == DoctorStatus.EDITED) {
                approvedClaims.add(new MlServiceClient.ApprovedClaimDto(
                        c.getId(),
                        c.getEditedCopy(),
                        c.getScientificClaim(),
                        c.getConsumerDraft(),
                        c.getQuote(),
                        c.getFindingClass() != null ? c.getFindingClass().name() : "PRIMARY",
                        parseNumbers(c.getNumbers()),
                        parseLimitations(c.getLimitations()),
                        c.getPopulation()
                ));
            }
        }

        MlServiceClient.PaperMetaDto metaDto = new MlServiceClient.PaperMetaDto(
                paper.getTitle(), paper.getAuthors(), paper.getYear(), paper.getJournal(), paper.getDoi()
        );

        MlServiceClient.WriteResponse writeRes = mlServiceClient.write(
                paperId, metaDto, approvedClaims, paper.getTheme(), asset.getLanguage(), asset.getType().name()
        );

        Map<String, Object> contentMap = switch (asset.getType()) {
            case CAROUSEL -> writeRes.carousel();
            case STATIC -> writeRes.staticBlock();
            case REEL -> writeRes.reel();
            case CAPTION -> writeRes.caption();
        };

        try {
            AssetEntity saved = saveAndCheckAsset(paperId, asset.getType(), contentMap, claimsById);
            if (saved != null && saved.getStatus() != AssetStatus.BLOCKED) {
                if (saved.getType() == AssetType.CAPTION) {
                    saved.setStatus(AssetStatus.RENDERED);
                    assetRepository.save(saved);
                } else {
                    reRenderAsset(saved, true);
                    saved.setStatus(AssetStatus.RENDERED);
                    assetRepository.save(saved);
                }
            }
        } catch (Exception e) {
            log.error("Failed to regenerate asset {}: {}", asset.getId(), e.getMessage(), e);
        }
    }

    public void reRenderAsset(AssetEntity asset, boolean draft) throws Exception {
        String paperId = asset.getPaperId();
        PaperEntity paper = paperRepository.findById(paperId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Paper not found: " + paperId));
        String citation = com.steerclub.steerproof.common.CitationHelper.formatShortCitation(paper.getAuthors(), paper.getYear(), paper.getJournal());

        if (asset.getType() == AssetType.CAROUSEL) {
            JsonNode content = objectMapper.readTree(asset.getContent());
            JsonNode slidesNode = content.has("slides") ? content.get("slides") : content;
            List<Map<String, Object>> slidesList = objectMapper.convertValue(slidesNode, new TypeReference<List<Map<String, Object>>>() {});
            RenderServiceClient.RenderResult res = renderServiceClient.renderCarousel(paperId, slidesList, citation, draft);
            asset.setRenderPaths(objectMapper.writeValueAsString(res.paths()));
            if (content.isObject()) {
                ((ObjectNode) content).set("blocks", objectMapper.valueToTree(res.blocks()));
                asset.setContent(objectMapper.writeValueAsString(content));
            }
        } else if (asset.getType() == AssetType.STATIC) {
            JsonNode content = objectMapper.readTree(asset.getContent());
            Map<String, Object> staContentMap = objectMapper.convertValue(content, new TypeReference<Map<String, Object>>() {});
            RenderServiceClient.RenderResult res = renderServiceClient.renderStatic(paperId, staContentMap, citation, draft);
            asset.setRenderPaths(objectMapper.writeValueAsString(res.paths()));
            if (content.isObject()) {
                ((ObjectNode) content).set("blocks", objectMapper.valueToTree(res.blocks()));
                asset.setContent(objectMapper.writeValueAsString(content));
            }
        } else if (asset.getType() == AssetType.REEL) {
            JsonNode content = objectMapper.readTree(asset.getContent());
            JsonNode scenesNode = content.has("scenes") ? content.get("scenes") : content;
            List<Map<String, Object>> scenesList = objectMapper.convertValue(scenesNode, new TypeReference<List<Map<String, Object>>>() {});
            Map<String, Object> res = renderServiceClient.renderReel(paperId, scenesList, citation, draft);
            if (res != null && res.containsKey("path")) {
                asset.setRenderPaths(objectMapper.writeValueAsString(List.of(res.get("path"))));
            }
        }
        assetRepository.save(asset);
    }
}
