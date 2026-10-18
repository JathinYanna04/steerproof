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
    private AuditService auditService;

    private static int testCounter = 100;

    private String createTestPaper() {
        String paperId = "P-TEST-REV-" + (++testCounter);
        PaperEntity paper = new PaperEntity(paperId, "papers/test.pdf", PaperStatus.AWAITING_CLAIM_REVIEW);
        paperRepository.save(paper);
        return paperId;
    }

    private ClaimEntity createClaim(String paperId, String claimId, DoctorStatus status) {
        ClaimEntity c = new ClaimEntity();
        c.setId(claimId);
        c.setPaperId(paperId);
        c.setQuote("Sample quote text for " + claimId);
        c.setScientificClaim("Sample scientific claim for " + claimId);
        c.setConsumerDraft("Sample consumer draft for " + claimId);
        c.setFindingClass(FindingClass.PRIMARY);
        c.setDoctorStatus(status);
        return claimRepository.save(c);
    }

    @Test
    void gate1BlocksWithPendingClaims() {
        String paperId = createTestPaper();
        createClaim(paperId, "C-REV-101", DoctorStatus.APPROVED);
        createClaim(paperId, "C-REV-102", DoctorStatus.APPROVED);
        createClaim(paperId, "C-REV-103", DoctorStatus.APPROVED);
        createClaim(paperId, "C-REV-104", DoctorStatus.PENDING);

        ReviewService.Gate1Result res = reviewService.checkGate1(paperId);
        assertFalse(res.ok());
        assertTrue(res.pending().contains("C-REV-104"));
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
        createClaim(paperId, "C-REV-201", DoctorStatus.APPROVED);
        createClaim(paperId, "C-REV-202", DoctorStatus.APPROVED);
        createClaim(paperId, "C-REV-203", DoctorStatus.APPROVED);
        createClaim(paperId, "C-REV-204", DoctorStatus.NEEDS_EVIDENCE);

        ReviewService.Gate1Result res = reviewService.checkGate1(paperId);
        assertFalse(res.ok());
        assertTrue(res.needsEvidence().contains("C-REV-204"));
        assertEquals(3, res.approvedCount());
    }

    @Test
    void gate1BlocksWithFewerThan3Approved() {
        String paperId = createTestPaper();
        createClaim(paperId, "C-REV-301", DoctorStatus.APPROVED);
        createClaim(paperId, "C-REV-302", DoctorStatus.APPROVED);
        createClaim(paperId, "C-REV-303", DoctorStatus.REJECTED);

        ReviewService.Gate1Result res = reviewService.checkGate1(paperId);
        assertFalse(res.ok());
        assertEquals(0, res.pending().size());
        assertEquals(0, res.needsEvidence().size());
        assertEquals(2, res.approvedCount());
    }

    @Test
    void gate1PassesWith3ApprovedAndNonePendingOrNeedsEvidence() {
        String paperId = createTestPaper();
        createClaim(paperId, "C-REV-401", DoctorStatus.APPROVED);
        createClaim(paperId, "C-REV-402", DoctorStatus.EDITED);
        createClaim(paperId, "C-REV-403", DoctorStatus.APPROVED);
        createClaim(paperId, "C-REV-404", DoctorStatus.REJECTED);

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
        String claimId = "C-REV-501";
        createClaim(paperId, claimId, DoctorStatus.NEEDS_EVIDENCE);

        ClaimService.ClaimReviewRequest req = new ClaimService.ClaimReviewRequest(
                "APPROVE", "Dr. Test", null, null, null, 10
        );

        ApiException ex = assertThrows(ApiException.class, () -> claimService.reviewClaim(claimId, req));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("ACTION_NOT_ALLOWED", ex.getErrorCode());
    }

    @Test
    void rejectWithoutNoteReturns400() {
        String paperId = createTestPaper();
        String claimId = "C-REV-601";
        createClaim(paperId, claimId, DoctorStatus.PENDING);

        ClaimService.ClaimReviewRequest req = new ClaimService.ClaimReviewRequest(
                "REJECT", "Dr. Test", null, null, null, 10
        );

        ApiException ex = assertThrows(ApiException.class, () -> claimService.reviewClaim(claimId, req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("NOTE_REQUIRED", ex.getErrorCode());
    }

    @Test
    void rejectingClaimUsedInAssetMarksAssetStale() {
        String paperId = createTestPaper();
        String claimId = "C-REV-701";
        createClaim(paperId, claimId, DoctorStatus.APPROVED);

        String assetId = "A-REV-701-CAR";
        AssetEntity asset = new AssetEntity();
        asset.setId(assetId);
        asset.setPaperId(paperId);
        asset.setType(AssetType.CAROUSEL);
        asset.setContent("{}");
        asset.setStatus(AssetStatus.RENDERED);
        assetRepository.save(asset);

        assetClaimRepository.save(new AssetClaimEntity(assetId, claimId, "slide:1"));

        ClaimService.ClaimReviewRequest req = new ClaimService.ClaimReviewRequest(
                "REJECT", "Dr. Sharma", null, null, "Contradicts primary findings", 25
        );

        ClaimService.ClaimReviewResponse resp = claimService.reviewClaim(claimId, req);
        assertEquals(DoctorStatus.REJECTED.name(), resp.claim().get("doctorStatus"));
        assertTrue(resp.staleAssets().contains(assetId));

        AssetEntity updatedAsset = assetRepository.findById(assetId).orElseThrow();
        assertEquals(AssetStatus.STALE, updatedAsset.getStatus());
    }

    @Test
    void humanEventsAndAuditLoggedOnReview() {
        String paperId = createTestPaper();
        String claimId = "C-REV-801";
        createClaim(paperId, claimId, DoctorStatus.PENDING);

        ClaimService.ClaimReviewRequest req = new ClaimService.ClaimReviewRequest(
                "APPROVE", "Dr. Kahan", null, null, null, 42
        );

        claimController.patchClaim(claimId, req);

        // Verify human event
        List<HumanEventEntity> events = humanEventRepository.findByPaperIdOrderByAtAsc(paperId);
        assertFalse(events.isEmpty());
        HumanEventEntity lastEvent = events.get(events.size() - 1);
        assertEquals("Dr. Kahan", lastEvent.getActor());
        assertEquals("CLAIM_REVIEW", lastEvent.getKind());
        assertEquals(42, lastEvent.getSeconds());

        // Verify audit log
        List<AuditService.AuditLogEntry> audit = auditService.getAuditHistory(claimId);
        assertFalse(audit.isEmpty());
        AuditService.AuditLogEntry entry = audit.get(audit.size() - 1);
        assertEquals("CLAIM_APPROVE", entry.action());
        assertEquals("Dr. Kahan", entry.actor());
        assertEquals("claim", entry.entity());
        assertEquals(claimId, entry.entityId());
    }
}
