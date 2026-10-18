package com.steerclub.steerproof.paper;

import com.steerclub.steerproof.claim.ClaimEntity;
import com.steerclub.steerproof.claim.ClaimRepository;
import com.steerclub.steerproof.common.ApiException;
import com.steerclub.steerproof.common.DoctorStatus;
import com.steerclub.steerproof.common.FindingClass;
import com.steerclub.steerproof.common.PaperStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class RetryIdempotencyTest {

    @Autowired
    private PaperController paperController;

    @Autowired
    private PaperRepository paperRepository;

    @Autowired
    private ClaimRepository claimRepository;

    @Test
    void retryOnNonFailedPaperReturns409() {
        PaperEntity paper = new PaperEntity("P-TEST-01", "papers/test.pdf", PaperStatus.PARSING);
        paperRepository.save(paper);

        ApiException ex = assertThrows(ApiException.class, () -> paperController.retryPaper("P-TEST-01"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("NOT_FAILED", ex.getErrorCode());
    }

    @Test
    void reviewedClaimStatusIsPreservedOnRerun() {
        String paperId = "P-TEST-02";
        PaperEntity paper = new PaperEntity(paperId, "papers/test.pdf", PaperStatus.AWAITING_CLAIM_REVIEW);
        paperRepository.save(paper);

        String claimId = "C-TEST-001";
        ClaimEntity claim = new ClaimEntity();
        claim.setId(claimId);
        claim.setPaperId(paperId);
        claim.setQuote("Test quote");
        claim.setScientificClaim("Test claim");
        claim.setFindingClass(FindingClass.PRIMARY);
        claim.setDoctorStatus(DoctorStatus.APPROVED);
        claim.setDoctorName("Dr. Rao");
        claimRepository.save(claim);

        // Verify status is APPROVED
        ClaimEntity loaded = claimRepository.findById(claimId).orElseThrow();
        assertEquals(DoctorStatus.APPROVED, loaded.getDoctorStatus());
        assertEquals("Dr. Rao", loaded.getDoctorName());
    }
}
