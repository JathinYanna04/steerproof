package com.steerclub.steerproof.asset;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steerclub.steerproof.audit.AuditService;
import com.steerclub.steerproof.claim.ClaimEntity;
import com.steerclub.steerproof.claim.ClaimRepository;
import com.steerclub.steerproof.common.ApiException;
import com.steerclub.steerproof.common.AssetStatus;
import com.steerclub.steerproof.common.DoctorStatus;
import com.steerclub.steerproof.common.Severity;
import com.steerclub.steerproof.paper.PaperEntity;
import com.steerclub.steerproof.paper.PaperRepository;
import com.steerclub.steerproof.pipeline.MlServiceClient;
import com.steerclub.steerproof.pipeline.PipelineService;
import com.steerclub.steerproof.policy.PolicyChecker;
import com.steerclub.steerproof.policy.PolicyViolationEntity;
import com.steerclub.steerproof.policy.PolicyViolationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api")
public class AssetController {

    private final AssetRepository assetRepository;
    private final AssetClaimRepository assetClaimRepository;
    private final PolicyViolationRepository policyViolationRepository;
    private final PaperRepository paperRepository;
    private final ClaimRepository claimRepository;
    private final PipelineService pipelineService;
    private final PolicyChecker policyChecker;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AssetController(
            AssetRepository assetRepository,
            AssetClaimRepository assetClaimRepository,
            PolicyViolationRepository policyViolationRepository,
            PaperRepository paperRepository,
            ClaimRepository claimRepository,
            PipelineService pipelineService,
            PolicyChecker policyChecker,
            AuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.assetRepository = assetRepository;
        this.assetClaimRepository = assetClaimRepository;
        this.policyViolationRepository = policyViolationRepository;
        this.paperRepository = paperRepository;
        this.claimRepository = claimRepository;
        this.pipelineService = pipelineService;
        this.policyChecker = policyChecker;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/papers/{paperId}/assets")
    public ResponseEntity<List<Map<String, Object>>> getAssetsForPaper(@PathVariable("paperId") String paperId) {
        List<AssetEntity> assets = assetRepository.findByPaperIdOrderByIdAsc(paperId);
        List<Map<String, Object>> result = new ArrayList<>();

        for (AssetEntity a : assets) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("assetId", a.getId());
            map.put("paperId", a.getPaperId());
            map.put("type", a.getType().name());
            map.put("language", a.getLanguage());
            map.put("status", a.getStatus().name());
            map.put("version", a.getVersion());

            Object contentObj = null;
            if (a.getContent() != null && !a.getContent().isBlank()) {
                try {
                    contentObj = objectMapper.readValue(a.getContent(), Object.class);
                } catch (Exception e) {
                    contentObj = a.getContent();
                }
            }
            map.put("content", contentObj);

            Object renderPathsObj = List.of();
            if (a.getRenderPaths() != null && !a.getRenderPaths().isBlank()) {
                try {
                    renderPathsObj = objectMapper.readValue(a.getRenderPaths(), Object.class);
                } catch (Exception e) {
                    renderPathsObj = List.of();
                }
            }
            map.put("renderUrls", renderPathsObj);

            List<PolicyViolationEntity> violations = policyViolationRepository.findByAssetId(a.getId());
            List<Map<String, Object>> vList = new ArrayList<>();
            for (PolicyViolationEntity v : violations) {
                Map<String, Object> vMap = new LinkedHashMap<>();
                vMap.put("id", v.getId());
                vMap.put("rule", v.getRule());
                vMap.put("severity", v.getSeverity().name());
                vMap.put("detail", v.getDetail());
                vMap.put("location", v.getLocation());
                vMap.put("claimId", v.getClaimId());
                vMap.put("resolved", v.getResolved());
                vList.add(vMap);
            }
            map.put("violations", vList);

            List<AssetClaimEntity> acList = assetClaimRepository.findByAssetId(a.getId());
            Set<String> uniqueClaimIds = new LinkedHashSet<>();
            for (AssetClaimEntity ac : acList) {
                if (ac.getClaimId() != null) uniqueClaimIds.add(ac.getClaimId());
            }
            map.put("claimIds", new ArrayList<>(uniqueClaimIds));
            map.put("scheduledFor", a.getScheduledFor());
            map.put("approvedBy", a.getApprovedBy());
            map.put("approvedAt", a.getApprovedAt());

            result.add(map);
        }

        return ResponseEntity.ok(result);
    }

    @PostMapping("/assets/{assetId}/regenerate")
    public ResponseEntity<Map<String, String>> regenerateAsset(@PathVariable("assetId") String assetId) {
        AssetEntity asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Asset not found: " + assetId));

        pipelineService.regenerateSingleAsset(asset);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "assetId", assetId,
                "status", "REGENERATING"
        ));
    }

    @PatchMapping("/assets/{assetId}/schedule")
    public ResponseEntity<AssetEntity> scheduleAsset(
            @PathVariable("assetId") String assetId,
            @RequestBody Map<String, String> body
    ) {
        AssetEntity asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Asset not found: " + assetId));

        String dateStr = body.get("date");
        if (dateStr == null || dateStr.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "date is required");
        }

        LocalDate date = LocalDate.parse(dateStr);
        asset.setScheduledFor(date);
        assetRepository.save(asset);

        auditService.log("doctor", "ASSET_SCHEDULED", "asset", assetId, Map.of(), Map.of("scheduledFor", dateStr));
        return ResponseEntity.ok(asset);
    }
}
