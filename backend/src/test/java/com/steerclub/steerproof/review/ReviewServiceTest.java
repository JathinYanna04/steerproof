package com.steerclub.steerproof.review;

import com.steerclub.steerproof.asset.AssetClaimEntity;
import com.steerclub.steerproof.asset.AssetClaimRepository;
import com.steerclub.steerproof.asset.AssetEntity;
import com.steerclub.steerproof.asset.AssetRepository;
import com.steerclub.steerproof.audit.AuditService;
import com.steerclub.steerproof.claim.ClaimController;
import com.steerclub.steerproof.claim.ClaimEntity;
import com.steerclub.steerproof.claim.ClaimRepository;
import com.steerclub.steerproof.claim.ClaimService;
import com.steerclub.steerproof.common.*;
import com.steerclub.steerproof.metric.HumanEventEntity;
import com.steerclub.steerproof.metric.HumanEventRepository;
import com.steerclub.steerproof.paper.PaperController;
import com.steerclub.steerproof.paper.PaperEntity;
import com.steerclub.steerproof.paper.PaperRepository;
import com.steerclub.steerproof.pipeline.PipelineRunEntity;
import com.steerclub.steerproof.pipeline.PipelineRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class ReviewServiceTest {

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private ClaimService claimService;

    @Autowired
    private ClaimController claimController;

    @Autowired
    private PaperController paperController;

    @Autowired
    private PaperRepository paperRepository;

    @Autowired
    private ClaimRepository claimRepository;

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private AssetClaimRepository assetClaimRepository;

    @Autowired
    private HumanEventRepository humanEventRepository;

    @Autowired
    private PipelineRunRepository pipelineRunRepository;

    @Autowired
    private AuditService auditService;

    private static int testCounter = (int) (System.currentTimeMillis() % 100000);

    private String createTestPaper() {
        String paperId = "P-TEST-REV-" + (++testCounter);
        PaperEntity paper = new PaperEntity(paperId, "papers/test.pdf", PaperStatus.AWAITING_CLAIM_REVIEW);
        paperRepository.save(paper);
        return paperId;
    }

    private ClaimEntity createClaim(String paperId, String claimId, DoctorStatus status) {
        String fullClaimId = claimId.startsWith("C-") ? claimId + "-" + paperId : "C-" + paperId + "-" + claimId;
        ClaimEntity c = new ClaimEntity();
        c.setId(fullClaimId);
        c.setPaperId(paperId);
        c.setQuote("Sample quote text for " + fullClaimId);
        c.setScientificClaim("Sample scientific claim for " + fullClaimId);
        c.setConsumerDraft("Sample consumer draft for " + fullClaimId);
        c.setFindingClass(FindingClass.PRIMARY);
        c.setDoctorStatus(status);
        return claimRepository.save(c);
    }

    @Test
    void gate1BlocksWithPendingClaims() {
        String paperId = createTestPaper();
        createClaim(paperId, "101", DoctorStatus.APPROVED);
        createClaim(paperId, "102", DoctorStatus.APPROVED);
        createClaim(paperId, "103", DoctorStatus.APPROVED);
        ClaimEntity pending = createClaim(paperId, "104", DoctorStatus.PENDING);

        ReviewService.Gate1Result res = reviewService.checkGate1(paperId);
        assertFalse(res.ok());
        assertTrue(res.pending().contains(pending.getId()));
        assertEquals(3, res.approvedCount());

        ResponseEntity<?> response = paperController.generatePaperAssets(paperId);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals("GATE1_BLOCKED", body.get("error"));
    }

    @Test
    void gate1BlocksWithNeedsEvidenceClaims() {
        String paperId = createTestPaper();
        createClaim(paperId, "201", DoctorStatus.APPROVED);
        createClaim(paperId, "202", DoctorStatus.APPROVED);
        createClaim(paperId, "203", DoctorStatus.APPROVED);
        ClaimEntity needsEv = createClaim(paperId, "204", DoctorStatus.NEEDS_EVIDENCE);

        ReviewService.Gate1Result res = reviewService.checkGate1(paperId);
        assertFalse(res.ok());
        assertTrue(res.needsEvidence().contains(needsEv.getId()));
        assertEquals(3, res.approvedCount());
    }

    @Test
    void gate1BlocksWithFewerThan3Approved() {
        String paperId = createTestPaper();
        createClaim(paperId, "301", DoctorStatus.APPROVED);
        createClaim(paperId, "302", DoctorStatus.APPROVED);
        createClaim(paperId, "303", DoctorStatus.REJECTED);

        ReviewService.Gate1Result res = reviewService.checkGate1(paperId);
        assertFalse(res.ok());
        assertEquals(0, res.pending().size());
        assertEquals(0, res.needsEvidence().size());
        assertEquals(2, res.approvedCount());
    }

    @Test
    void gate1PassesWith3ApprovedAndNonePendingOrNeedsEvidence() {
        String paperId = createTestPaper();
        createClaim(paperId, "401", DoctorStatus.APPROVED);
        createClaim(paperId, "402", DoctorStatus.EDITED);
        createClaim(paperId, "403", DoctorStatus.APPROVED);
        createClaim(paperId, "404", DoctorStatus.REJECTED);

        ReviewService.Gate1Result res = reviewService.checkGate1(paperId);
        assertTrue(res.ok());
        assertEquals(3, res.approvedCount());

        ResponseEntity<?> response = paperController.generatePaperAssets(paperId);
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        PaperEntity updatedPaper = paperRepository.findById(paperId).orElseThrow();
        assertEquals(PaperStatus.WRITING, updatedPaper.getStatus());
    }

    @Test
    void approveOnNeedsEvidenceReturns409() {
        String paperId = createTestPaper();
        ClaimEntity claim = createClaim(paperId, "501", DoctorStatus.NEEDS_EVIDENCE);

        ClaimService.ClaimReviewRequest req = new ClaimService.ClaimReviewRequest(
                "APPROVE", "Test Reviewer", null, null, null, 10
        );

        ApiException ex = assertThrows(ApiException.class, () -> claimService.reviewClaim(claim.getId(), req));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("ACTION_NOT_ALLOWED", ex.getErrorCode());
    }

    @Test
    void rejectWithoutNoteReturns400() {
        String paperId = createTestPaper();
        ClaimEntity claim = createClaim(paperId, "601", DoctorStatus.PENDING);

        ClaimService.ClaimReviewRequest req = new ClaimService.ClaimReviewRequest(
                "REJECT", "Test Reviewer", null, null, null, 10
        );

        ApiException ex = assertThrows(ApiException.class, () -> claimService.reviewClaim(claim.getId(), req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("NOTE_REQUIRED", ex.getErrorCode());
    }

    @Test
    void rejectingClaimUsedInAssetMarksAssetStale() {
        String paperId = createTestPaper();
        ClaimEntity claim = createClaim(paperId, "701", DoctorStatus.APPROVED);

        String assetId = "A-REV-" + paperId + "-CAR";
        AssetEntity asset = new AssetEntity();
        asset.setId(assetId);
        asset.setPaperId(paperId);
        asset.setType(AssetType.CAROUSEL);
        asset.setContent("{}");
        asset.setStatus(AssetStatus.RENDERED);
        assetRepository.save(asset);

        assetClaimRepository.save(new AssetClaimEntity(assetId, claim.getId(), "slide:1"));

        ClaimService.ClaimReviewRequest req = new ClaimService.ClaimReviewRequest(
                "REJECT", "Test Reviewer", null, null, "Contradicts primary findings", 25
        );

        ClaimService.ClaimReviewResponse resp = claimService.reviewClaim(claim.getId(), req);
        assertEquals(DoctorStatus.REJECTED.name(), resp.claim().get("doctorStatus"));
        assertTrue(resp.staleAssets().contains(assetId));

        AssetEntity updatedAsset = assetRepository.findById(assetId).orElseThrow();
        assertEquals(AssetStatus.STALE, updatedAsset.getStatus());
    }

    @Test
    void humanEventsAndAuditLoggedOnReview() {
        String paperId = createTestPaper();
        ClaimEntity claim = createClaim(paperId, "801", DoctorStatus.PENDING);

        ClaimService.ClaimReviewRequest req = new ClaimService.ClaimReviewRequest(
                "APPROVE", "Test Reviewer", null, null, null, 42
        );

        claimController.patchClaim(claim.getId(), req);

        // Verify human event
        List<HumanEventEntity> events = humanEventRepository.findByPaperIdOrderByAtAsc(paperId);
        assertFalse(events.isEmpty());
        HumanEventEntity lastEvent = events.get(events.size() - 1);
        assertEquals("Test Reviewer", lastEvent.getActor());
        assertEquals("CLAIM_REVIEW", lastEvent.getKind());
        assertEquals(42, lastEvent.getSeconds());

        // Verify audit log
        List<AuditService.AuditLogEntry> audit = auditService.getAuditHistory(claim.getId());
        assertFalse(audit.isEmpty());
        AuditService.AuditLogEntry entry = audit.get(audit.size() - 1);
        assertEquals("CLAIM_APPROVE", entry.action());
        assertEquals("Test Reviewer", entry.actor());
        assertEquals("claim", entry.entity());
        assertEquals(claim.getId(), entry.entityId());
    }

    @Test
    void metricsCalculationClaimsAutoFlaggedAndPipelineSeconds() {
        String paperId = createTestPaper();

        ClaimEntity c1 = createClaim(paperId, "C-REV-901", DoctorStatus.APPROVED);
        c1.setRisk(Risk.GREEN);
        claimRepository.save(c1);

        ClaimEntity c2 = createClaim(paperId, "C-REV-902", DoctorStatus.APPROVED);
        c2.setRisk(Risk.AMBER);
        claimRepository.save(c2);

        ClaimEntity c3 = createClaim(paperId, "C-REV-903", DoctorStatus.APPROVED);
        c3.setRisk(Risk.RED);
        claimRepository.save(c3);

        ClaimEntity c4 = createClaim(paperId, "C-REV-904", DoctorStatus.NEEDS_EVIDENCE);
        c4.setRisk(Risk.GREEN);
        claimRepository.save(c4);

        java.time.Instant now = java.time.Instant.now();
        PipelineRunEntity parseRun = new PipelineRunEntity(paperId, "PARSE", now.minusSeconds(120));
        parseRun.setEndedAt(now.minusSeconds(100));
        parseRun.setOk(true);
        pipelineRunRepository.save(parseRun);

        PipelineRunEntity verifyRun = new PipelineRunEntity(paperId, "VERIFY", now.minusSeconds(90));
        verifyRun.setEndedAt(now.minusSeconds(30));
        verifyRun.setOk(true);
        pipelineRunRepository.save(verifyRun);

        Map<String, Object> paper = paperController.getPaper(paperId);
        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) paper.get("stats");
        assertNotNull(stats);

        // c2 (AMBER), c3 (RED), c4 (NEEDS_EVIDENCE) should be autoFlagged = 3
        assertEquals(3, stats.get("claimsAutoFlagged"));

        // latest successful run only: PARSE start to VERIFY end (120 - 30 = 90 seconds)
        assertEquals(90.0, stats.get("pipelineSeconds"));
    }

    @Test
    void resetPaperClearsClaimsAndResetsStatus() {
        String paperId = createTestPaper();
        createClaim(paperId, "C-REV-951", DoctorStatus.APPROVED);
        createClaim(paperId, "C-REV-952", DoctorStatus.APPROVED);

        ResponseEntity<?> resetResp = paperController.resetPaper(paperId);
        assertEquals(HttpStatus.OK, resetResp.getStatusCode());

        List<ClaimEntity> remainingClaims = claimRepository.findByPaperIdOrderByIdAsc(paperId);
        assertTrue(remainingClaims.isEmpty());
    }
}
