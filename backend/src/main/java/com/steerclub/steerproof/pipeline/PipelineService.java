package com.steerclub.steerproof.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steerclub.steerproof.audit.AuditService;
import com.steerclub.steerproof.claim.ClaimEntity;
import com.steerclub.steerproof.claim.ClaimRepository;
import com.steerclub.steerproof.common.*;
import com.steerclub.steerproof.paper.PaperEntity;
import com.steerclub.steerproof.paper.PaperRepository;
import com.steerclub.steerproof.passage.PassageEntity;
import com.steerclub.steerproof.passage.PassageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

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

    public PipelineService(
            PaperRepository paperRepository,
            PassageRepository passageRepository,
            ClaimRepository claimRepository,
            PipelineRunRepository pipelineRunRepository,
            MlServiceClient mlServiceClient,
            IdGenerator idGenerator,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.paperRepository = paperRepository;
        this.passageRepository = passageRepository;
        this.claimRepository = claimRepository;
        this.pipelineRunRepository = pipelineRunRepository;
        this.mlServiceClient = mlServiceClient;
        this.idGenerator = idGenerator;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Async
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

                for (int i = 0; i < extractedClaims.size(); i++) {
                    MlServiceClient.ExtractedClaimDto raw = extractedClaims.get(i);
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
}
