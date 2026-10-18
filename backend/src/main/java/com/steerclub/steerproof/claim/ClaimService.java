package com.steerclub.steerproof.claim;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steerclub.steerproof.asset.AssetClaimEntity;
import com.steerclub.steerproof.asset.AssetClaimRepository;
import com.steerclub.steerproof.asset.AssetEntity;
import com.steerclub.steerproof.asset.AssetRepository;
import com.steerclub.steerproof.audit.AuditService;
import com.steerclub.steerproof.common.*;
import com.steerclub.steerproof.metric.HumanEventEntity;
import com.steerclub.steerproof.metric.HumanEventRepository;
import com.steerclub.steerproof.passage.PassageEntity;
import com.steerclub.steerproof.passage.PassageRepository;
import com.steerclub.steerproof.pipeline.MlServiceClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class ClaimService {

    private final ClaimRepository claimRepository;
    private final PassageRepository passageRepository;
    private final AssetRepository assetRepository;
    private final AssetClaimRepository assetClaimRepository;
    private final HumanEventRepository humanEventRepository;
    private final AuditService auditService;
    private final MlServiceClient mlServiceClient;
    private final ObjectMapper objectMapper;

    public ClaimService(
            ClaimRepository claimRepository,
            PassageRepository passageRepository,
            AssetRepository assetRepository,
            AssetClaimRepository assetClaimRepository,
            HumanEventRepository humanEventRepository,
            AuditService auditService,
            MlServiceClient mlServiceClient,
            ObjectMapper objectMapper
    ) {
        this.claimRepository = claimRepository;
        this.passageRepository = passageRepository;
        this.assetRepository = assetRepository;
        this.assetClaimRepository = assetClaimRepository;
        this.humanEventRepository = humanEventRepository;
        this.auditService = auditService;
        this.mlServiceClient = mlServiceClient;
        this.objectMapper = objectMapper;
    }

    public record ClaimReviewRequest(
            String action,        // APPROVE | EDIT | REJECT
            String doctorName,
            String editedCopy,
            String passageId,
            String note,
            Integer reviewSeconds
    ) {}

    public record ClaimReviewResponse(
            Map<String, Object> claim,
            List<String> staleAssets
    ) {}

    @Transactional
    public ClaimReviewResponse reviewClaim(String claimId, ClaimReviewRequest req) {
        ClaimEntity claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Claim not found: " + claimId));

        Map<String, Object> beforeState = new LinkedHashMap<>();
        beforeState.put("doctorStatus", claim.getDoctorStatus().name());
        beforeState.put("editedCopy", claim.getEditedCopy());
        beforeState.put("passageId", claim.getPassageId());
        beforeState.put("doctorNote", claim.getDoctorNote());
        beforeState.put("doctorName", claim.getDoctorName());
        beforeState.put("quoteScore", claim.getQuoteScore());

        String action = req.action() != null ? req.action().trim().toUpperCase() : "";
        if (!List.of("APPROVE", "EDIT", "REJECT").contains(action)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ACTION", "Action must be APPROVE, EDIT, or REJECT");
        }

        switch (action) {
            case "APPROVE" -> {
                if (claim.getDoctorStatus() == DoctorStatus.NEEDS_EVIDENCE) {
                    throw new ApiException(HttpStatus.CONFLICT, "ACTION_NOT_ALLOWED",
                            "Cannot APPROVE claim with status NEEDS_EVIDENCE. Re-link evidence to EDIT or REJECT it.");
                }
                claim.setDoctorStatus(DoctorStatus.APPROVED);
            }
            case "REJECT" -> {
                if (req.note() == null || req.note().trim().isEmpty()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "NOTE_REQUIRED", "Note is required when rejecting a claim");
                }
                claim.setDoctorStatus(DoctorStatus.REJECTED);
                claim.setDoctorNote(req.note().trim());
            }
            case "EDIT" -> {
                if (req.editedCopy() != null) {
                    claim.setEditedCopy(req.editedCopy());
                }
                if (req.passageId() != null && !req.passageId().trim().isEmpty()) {
                    PassageEntity passage = passageRepository.findById(req.passageId().trim())
                            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Passage not found: " + req.passageId()));
                    if (!passage.getPaperId().equals(claim.getPaperId())) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSAGE", "Passage does not belong to this paper");
                    }
                    claim.setPassageId(passage.getId());
                    reVerifyClaim(claim, passage);
                } else {
                    claim.setDoctorStatus(DoctorStatus.EDITED);
                }
            }
        }

        if (req.doctorName() != null && !req.doctorName().trim().isEmpty()) {
            claim.setDoctorName(req.doctorName().trim());
        }
        claim.setReviewedAt(Instant.now());

        if (req.reviewSeconds() != null && req.reviewSeconds() > 0) {
            int current = claim.getReviewSeconds() != null ? claim.getReviewSeconds() : 0;
            claim.setReviewSeconds(current + req.reviewSeconds());
            humanEventRepository.save(new HumanEventEntity(
                    claim.getPaperId(),
                    claim.getDoctorName() != null ? claim.getDoctorName() : "Reviewer",
                    "CLAIM_REVIEW",
                    req.reviewSeconds()
            ));
        }

        claimRepository.save(claim);

        // Mark dependent assets STALE
        List<AssetClaimEntity> links = assetClaimRepository.findByClaimId(claimId);
        List<String> staleAssets = new ArrayList<>();
        for (AssetClaimEntity link : links) {
            assetRepository.findById(link.getAssetId()).ifPresent(asset -> {
                if (asset.getStatus() != AssetStatus.STALE) {
                    asset.setStatus(AssetStatus.STALE);
                    assetRepository.save(asset);
                }
                if (!staleAssets.contains(asset.getId())) {
                    staleAssets.add(asset.getId());
                }
            });
        }

        Map<String, Object> afterState = new LinkedHashMap<>();
        afterState.put("doctorStatus", claim.getDoctorStatus().name());
        afterState.put("editedCopy", claim.getEditedCopy());
        afterState.put("passageId", claim.getPassageId());
        afterState.put("doctorNote", claim.getDoctorNote());
        afterState.put("doctorName", claim.getDoctorName());
        afterState.put("quoteScore", claim.getQuoteScore());

        auditService.log(
                claim.getDoctorName() != null ? claim.getDoctorName() : "Reviewer",
                "CLAIM_" + action,
                "claim",
                claim.getId(),
                beforeState,
                afterState
        );

        PassageEntity passage = claim.getPassageId() != null
                ? passageRepository.findById(claim.getPassageId()).orElse(null)
                : null;

        return new ClaimReviewResponse(toClaimMap(claim, passage), staleAssets);
    }

    private void reVerifyClaim(ClaimEntity claim, PassageEntity passage) {
        Map<String, Double> bbox = parseBbox(passage.getBbox());
        MlServiceClient.PassageDto pDto = new MlServiceClient.PassageDto(
                passage.getId(),
                passage.getSection(),
                passage.getKind(),
                passage.getPage(),
                bbox,
                passage.getPageWidth() != null ? passage.getPageWidth().doubleValue() : null,
                passage.getPageHeight() != null ? passage.getPageHeight().doubleValue() : null,
                passage.getText(),
                passage.getOrd()
        );

        MlServiceClient.VerifyClaimDto vDto = new MlServiceClient.VerifyClaimDto(
                passage.getId(),
                claim.getQuote(),
                claim.getScientificClaim(),
                claim.getConsumerDraft(),
                parseNumbers(claim.getNumbers()),
                claim.getStudyDesign(),
                claim.getPopulation(),
                claim.getFindingClass() != null ? claim.getFindingClass().name() : "PRIMARY",
                parseLimitations(claim.getLimitations())
        );

        try {
            MlServiceClient.VerifyResponse res = mlServiceClient.verify(
                    claim.getPaperId(),
                    List.of(vDto),
                    List.of(pDto)
            );

            if (res != null && res.results() != null && !res.results().isEmpty()) {
                MlServiceClient.VerifyItemDetailDto detail = res.results().get(0);
                claim.setQuoteScore(detail.quoteScore());
                claim.setNumbersOk(detail.numbersOk());
                if (detail.entailment() != null) {
                    try {
                        claim.setEntailment(Entailment.valueOf(detail.entailment()));
                    } catch (Exception ignored) {}
                }
                if (detail.risk() != null) {
                    try {
                        claim.setRisk(Risk.valueOf(detail.risk()));
                    } catch (Exception ignored) {}
                }
                claim.setRiskReason(detail.riskReason());
                if (detail.populationFit() != null) {
                    try {
                        claim.setPopulationFit(PopulationFit.valueOf(detail.populationFit()));
                    } catch (Exception ignored) {}
                }
                claim.setPopulationReason(detail.populationReason());

                if (detail.quoteScore() >= 90) {
                    claim.setDoctorStatus(DoctorStatus.EDITED);
                } else {
                    claim.setDoctorStatus(DoctorStatus.NEEDS_EVIDENCE);
                }
            } else {
                claim.setDoctorStatus(DoctorStatus.EDITED);
            }
        } catch (Exception e) {
            claim.setDoctorStatus(DoctorStatus.EDITED);
        }
    }

    public Map<String, Object> getClaim(String claimId) {
        ClaimEntity claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Claim not found: " + claimId));
        PassageEntity passage = claim.getPassageId() != null
                ? passageRepository.findById(claim.getPassageId()).orElse(null)
                : null;
        return toClaimMap(claim, passage);
    }

    public List<Map<String, Object>> getClaimsByPaperId(String paperId) {
        List<ClaimEntity> claims = claimRepository.findByPaperIdOrderByIdAsc(paperId);
        Map<String, PassageEntity> passageMap = new HashMap<>();
        for (PassageEntity p : passageRepository.findByPaperIdOrderByOrdAsc(paperId)) {
            passageMap.put(p.getId(), p);
        }
        return claims.stream().map(c -> toClaimMap(c, passageMap.get(c.getPassageId()))).toList();
    }

    public Map<String, Object> toClaimMap(ClaimEntity c, PassageEntity passage) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("claimId", c.getId());
        map.put("paperId", c.getPaperId());
        map.put("scientificClaim", c.getScientificClaim());
        map.put("consumerDraft", c.getConsumerDraft());
        map.put("quote", c.getQuote());

        try {
            map.put("numbers", c.getNumbers() != null ? objectMapper.readValue(c.getNumbers(), Object.class) : List.of());
        } catch (Exception e) {
            map.put("numbers", List.of());
        }

        map.put("studyDesign", c.getStudyDesign());
        map.put("population", c.getPopulation());
        map.put("findingClass", c.getFindingClass() != null ? c.getFindingClass().name() : null);

        try {
            map.put("limitations", c.getLimitations() != null ? objectMapper.readValue(c.getLimitations(), Object.class) : List.of());
        } catch (Exception e) {
            map.put("limitations", List.of());
        }

        map.put("quoteScore", c.getQuoteScore());
        map.put("numbersOk", c.getNumbersOk());
        map.put("entailment", c.getEntailment() != null ? c.getEntailment().name() : null);
        map.put("risk", c.getRisk() != null ? c.getRisk().name() : null);
        map.put("riskReason", c.getRiskReason());
        map.put("populationFit", c.getPopulationFit() != null ? c.getPopulationFit().name() : null);
        map.put("populationReason", c.getPopulationReason());
        map.put("doctorStatus", c.getDoctorStatus().name());
        map.put("editedCopy", c.getEditedCopy());
        map.put("doctorName", c.getDoctorName());
        map.put("doctorNote", c.getDoctorNote());
        map.put("reviewSeconds", c.getReviewSeconds());
        map.put("reviewedAt", c.getReviewedAt());

        if (passage != null) {
            Map<String, Object> pMap = new LinkedHashMap<>();
            pMap.put("passageId", passage.getId());
            pMap.put("page", passage.getPage());
            try {
                pMap.put("bbox", passage.getBbox() != null ? objectMapper.readValue(passage.getBbox(), Object.class) : null);
            } catch (Exception e) {
                pMap.put("bbox", null);
            }
            pMap.put("pageWidth", passage.getPageWidth());
            pMap.put("pageHeight", passage.getPageHeight());
            pMap.put("section", passage.getSection());
            map.put("passage", pMap);
        } else {
            map.put("passage", null);
        }

        List<AssetClaimEntity> links = assetClaimRepository.findByClaimId(c.getId());
        List<Map<String, Object>> usedIn = links.stream()
                .map(l -> Map.<String, Object>of("assetId", l.getAssetId(), "location", l.getLocation()))
                .toList();
        map.put("usedIn", usedIn);

        return map;
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

    private Map<String, Double> parseBbox(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Double>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
