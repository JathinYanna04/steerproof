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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.steerclub.steerproof.metric.HumanEventEntity;
import com.steerclub.steerproof.metric.HumanEventRepository;

@RestController
@RequestMapping("/api")
public class AssetController {

    private static final Logger log = LoggerFactory.getLogger(AssetController.class);

    private final AssetRepository assetRepository;
    private final AssetClaimRepository assetClaimRepository;
    private final PolicyViolationRepository policyViolationRepository;
    private final PaperRepository paperRepository;
    private final ClaimRepository claimRepository;
    private final PipelineService pipelineService;
    private final PolicyChecker policyChecker;
    private final AuditService auditService;
    private final HumanEventRepository humanEventRepository;
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
            HumanEventRepository humanEventRepository,
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
        this.humanEventRepository = humanEventRepository;
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

    public record AssetApprovalRequest(
            String doctorName,
            Integer reviewSeconds,
            Boolean overrideWarnings
    ) {}

    @PostMapping("/assets/{assetId}/approve")
    @Transactional
    public ResponseEntity<?> approveAsset(
            @PathVariable("assetId") String assetId,
            @RequestBody(required = false) AssetApprovalRequest req
    ) {
        AssetEntity asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Asset not found: " + assetId));

        String doctorName = (req != null && req.doctorName() != null && !req.doctorName().isBlank())
                ? req.doctorName().trim()
                : "Jathin Reddy Yanna (non-clinician reviewer)";
        int reviewSec = (req != null && req.reviewSeconds() != null) ? req.reviewSeconds() : 0;
        boolean overrideWarnings = req != null && Boolean.TRUE.equals(req.overrideWarnings());

        // 1. Status must be RENDERED
        boolean isRendered = (asset.getStatus() == AssetStatus.RENDERED);

        // 2. Linked claims must all be APPROVED or EDITED
        List<AssetClaimEntity> links = assetClaimRepository.findByAssetId(assetId);
        List<Map<String, String>> unapprovedClaims = new ArrayList<>();
        for (AssetClaimEntity link : links) {
            if (link.getClaimId() != null) {
                ClaimEntity claim = claimRepository.findById(link.getClaimId()).orElse(null);
                if (claim != null) {
                    if (claim.getDoctorStatus() != DoctorStatus.APPROVED && claim.getDoctorStatus() != DoctorStatus.EDITED) {
                        unapprovedClaims.add(Map.of("claimId", claim.getId(), "status", claim.getDoctorStatus().name()));
                    }
                }
            }
        }

        // 3. Check violations
        List<PolicyViolationEntity> violations = policyViolationRepository.findByAssetId(assetId);
        List<Map<String, String>> blockingViolations = new ArrayList<>();
        List<Map<String, String>> warningViolations = new ArrayList<>();
        for (PolicyViolationEntity v : violations) {
            if (v.getSeverity() == Severity.BLOCKING && !Boolean.TRUE.equals(v.getResolved())) {
                blockingViolations.add(Map.of("rule", v.getRule(), "location", v.getLocation() != null ? v.getLocation() : "", "detail", v.getDetail() != null ? v.getDetail() : ""));
            } else if (v.getSeverity() == Severity.WARNING && !Boolean.TRUE.equals(v.getResolved())) {
                warningViolations.add(Map.of("rule", v.getRule(), "location", v.getLocation() != null ? v.getLocation() : "", "detail", v.getDetail() != null ? v.getDetail() : ""));
            }
        }

        // Return 422 if not rendered, or unapproved claims, or blocking violations
        if (!isRendered || !unapprovedClaims.isEmpty() || !blockingViolations.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("claims", unapprovedClaims);
            details.put("violations", blockingViolations);
            details.put("status", asset.getStatus().name());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "ASSET_BLOCKED",
                    "message", "Asset cannot be approved because it has unmet requirements",
                    "details", details
            ));
        }

        // If warning violations exist but overrideWarnings is false
        if (!warningViolations.isEmpty() && !overrideWarnings) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("claims", unapprovedClaims);
            details.put("violations", warningViolations);
            details.put("status", asset.getStatus().name());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "ASSET_BLOCKED",
                    "message", "Asset contains warnings that must be overridden with overrideWarnings: true",
                    "details", details
            ));
        }

        // If override warnings
        if (!warningViolations.isEmpty() && overrideWarnings) {
            auditService.log(doctorName, "WARNING_OVERRIDE", "asset", assetId, Map.of(), Map.of("warnings", warningViolations));
        }

        // Perform approval
        int oldVersion = asset.getVersion() != null ? asset.getVersion() : 1;
        asset.setStatus(AssetStatus.APPROVED);
        asset.setApprovedBy(doctorName);
        asset.setApprovedAt(Instant.now());
        asset.setVersion(oldVersion + 1);

        auditService.log(doctorName, "ASSET_APPROVED", "asset", assetId, Map.of("version", oldVersion), Map.of("version", asset.getVersion(), "status", "APPROVED"));
        humanEventRepository.save(new HumanEventEntity(asset.getPaperId(), doctorName, "ASSET_REVIEW", reviewSec));

        // Re-render asset with draft=false
        try {
            pipelineService.reRenderAsset(asset, false);
        } catch (Exception e) {
            log.error("Failed to re-render approved asset without watermark: {}", e.getMessage(), e);
        }

        assetRepository.save(asset);

        return ResponseEntity.ok(Map.of(
                "assetId", asset.getId(),
                "status", asset.getStatus().name(),
                "version", asset.getVersion(),
                "approvedBy", asset.getApprovedBy(),
                "approvedAt", asset.getApprovedAt()
        ));
    }
}
