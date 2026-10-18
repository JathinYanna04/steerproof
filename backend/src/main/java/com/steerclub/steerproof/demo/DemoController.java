package com.steerclub.steerproof.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steerclub.steerproof.asset.AssetClaimRepository;
import com.steerclub.steerproof.audit.AuditService;
import com.steerclub.steerproof.claim.ClaimEntity;
import com.steerclub.steerproof.claim.ClaimRepository;
import com.steerclub.steerproof.common.*;
import com.steerclub.steerproof.paper.PaperEntity;
import com.steerclub.steerproof.paper.PaperRepository;
import com.steerclub.steerproof.passage.PassageEntity;
import com.steerclub.steerproof.passage.PassageRepository;
import com.steerclub.steerproof.pipeline.MlServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final boolean demoMode;
    private final PaperRepository paperRepository;
    private final ClaimRepository claimRepository;
    private final PassageRepository passageRepository;
    private final MlServiceClient mlServiceClient;
    private final AuditService auditService;
    private final IdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final AssetClaimRepository assetClaimRepository;

    public DemoController(
            @Value("${app.demo-mode:false}") boolean demoMode,
            PaperRepository paperRepository,
            ClaimRepository claimRepository,
            PassageRepository passageRepository,
            MlServiceClient mlServiceClient,
            AuditService auditService,
            IdGenerator idGenerator,
            ObjectMapper objectMapper,
            AssetClaimRepository assetClaimRepository
    ) {
        this.demoMode = demoMode;
        this.paperRepository = paperRepository;
        this.claimRepository = claimRepository;
        this.passageRepository = passageRepository;
        this.mlServiceClient = mlServiceClient;
        this.auditService = auditService;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.assetClaimRepository = assetClaimRepository;
    }

    /** Removes the seeded bad claim(s) of a paper after a demo recording, so the real review starts clean. */
    @DeleteMapping("/seed-bad-claim/{paperId}")
    @Transactional
    public ResponseEntity<?> removeSeededClaims(@PathVariable("paperId") String paperId) {
        if (!demoMode) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEMO_MODE_REQUIRED", "Removing seeded claims is only allowed in DEMO_MODE");
        }
        List<String> removed = new ArrayList<>();
        for (ClaimEntity c : claimRepository.findByPaperIdOrderByIdAsc(paperId)) {
            if (!Boolean.TRUE.equals(c.getIsSeededBad())) continue;
            if (!assetClaimRepository.findByClaimId(c.getId()).isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "CLAIM_IN_USE",
                        c.getId() + " is used by a generated asset; reject it and regenerate the asset first");
            }
            claimRepository.delete(c);
            auditService.log("demo", "BAD_CLAIM_REMOVED", "claim", c.getId(),
                    Map.of("doctorStatus", c.getDoctorStatus().name()), Map.of());
            removed.add(c.getId());
        }
        return ResponseEntity.ok(Map.of("paperId", paperId, "removed", removed));
    }

    @PostMapping("/seed-bad-claim/{paperId}")
    @Transactional
    public ResponseEntity<?> seedBadClaim(@PathVariable("paperId") String paperId) {
        if (!demoMode) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEMO_MODE_REQUIRED", "Seeding bad claims is only allowed in DEMO_MODE");
        }

        PaperEntity paper = paperRepository.findById(paperId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Paper not found: " + paperId));

        // Find weight regain passage or first passage
        List<PassageEntity> passages = passageRepository.findByPaperIdOrderByOrdAsc(paperId);
        if (passages.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_PASSAGES", "Paper has no passages");
        }

        PassageEntity targetPassage = passages.stream()
                .filter(p -> p.getText() != null && p.getText().toLowerCase().contains("regain"))
                .findFirst()
                .orElse(passages.get(0));

        String claimText = "Weight always comes back fully when you stop treatment.";
        String claimId = paperId.replace("P-", "C-") + "-BAD";

        // Convert passage to PassageDto
        Map<String, Double> bboxMap = null;
        if (targetPassage.getBbox() != null) {
            try {
                bboxMap = objectMapper.readValue(targetPassage.getBbox(), new TypeReference<Map<String, Double>>() {});
            } catch (Exception ignored) {}
        }
        MlServiceClient.PassageDto passageDto = new MlServiceClient.PassageDto(
                targetPassage.getId(),
                targetPassage.getSection(),
                targetPassage.getKind(),
                targetPassage.getPage(),
                bboxMap,
                targetPassage.getPageWidth() != null ? targetPassage.getPageWidth().doubleValue() : null,
                targetPassage.getPageHeight() != null ? targetPassage.getPageHeight().doubleValue() : null,
                targetPassage.getText(),
                targetPassage.getOrd()
        );

        MlServiceClient.VerifyClaimDto verifyDto = new MlServiceClient.VerifyClaimDto(
                targetPassage.getId(),
                targetPassage.getText(),
                claimText,
                claimText,
                List.of(),
                "Randomised controlled trial",
                "Adults with overweight or obesity",
                "PRIMARY",
                List.of()
        );

        // Run REAL verifier
        MlServiceClient.VerifyResponse verifyRes = mlServiceClient.verify(
                paperId,
                List.of(verifyDto),
                List.of(passageDto)
        );

        MlServiceClient.VerifyItemDetailDto detail = (!verifyRes.results().isEmpty()) ? verifyRes.results().get(0) : null;

        ClaimEntity claim = claimRepository.findById(claimId).orElseGet(ClaimEntity::new);
        claim.setId(claimId);
        claim.setPaperId(paperId);
        claim.setPassageId(targetPassage.getId());
        claim.setQuote(targetPassage.getText());
        claim.setScientificClaim(claimText);
        claim.setConsumerDraft(claimText);
        claim.setFindingClass(FindingClass.PRIMARY);
        claim.setStudyDesign("Randomised controlled trial");
        claim.setPopulation("Adults with overweight or obesity");
        claim.setDoctorStatus(DoctorStatus.PENDING);
        claim.setIsSeededBad(true);

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

            // Risk floor:
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
        }

        ClaimEntity saved = claimRepository.save(claim);

        auditService.log("demo", "BAD_CLAIM_SEEDED", "claim", saved.getId(), Map.of(), Map.of(
                "risk", saved.getRisk() != null ? saved.getRisk().name() : "null",
                "entailment", saved.getEntailment() != null ? saved.getEntailment().name() : "null",
                "isSeededBad", true
        ));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("claimId", saved.getId());
        resp.put("paperId", saved.getPaperId());
        resp.put("passageId", saved.getPassageId());
        resp.put("scientificClaim", saved.getScientificClaim());
        resp.put("consumerDraft", saved.getConsumerDraft());
        resp.put("quoteScore", saved.getQuoteScore());
        resp.put("numbersOk", saved.getNumbersOk());
        resp.put("entailment", saved.getEntailment() != null ? saved.getEntailment().name() : null);
        resp.put("risk", saved.getRisk() != null ? saved.getRisk().name() : null);
        resp.put("riskReason", saved.getRiskReason());
        resp.put("populationFit", saved.getPopulationFit() != null ? saved.getPopulationFit().name() : null);
        resp.put("populationReason", saved.getPopulationReason());
        resp.put("doctorStatus", saved.getDoctorStatus().name());
        resp.put("isSeededBad", saved.getIsSeededBad());

        return ResponseEntity.ok(resp);
    }
}
