package com.steerclub.steerproof.review;

import com.steerclub.steerproof.claim.ClaimEntity;
import com.steerclub.steerproof.claim.ClaimRepository;
import com.steerclub.steerproof.common.DoctorStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ReviewService {

    private final ClaimRepository claimRepository;

    public ReviewService(ClaimRepository claimRepository) {
        this.claimRepository = claimRepository;
    }

    public record Gate1Result(
            boolean ok,
            List<String> pending,
            List<String> needsEvidence,
            int approvedCount
    ) {}

    public Gate1Result checkGate1(String paperId) {
        List<ClaimEntity> claims = claimRepository.findByPaperIdOrderByIdAsc(paperId);
        List<String> pending = new ArrayList<>();
        List<String> needsEvidence = new ArrayList<>();
        int approvedCount = 0;

        for (ClaimEntity c : claims) {
            if (c.getDoctorStatus() == DoctorStatus.PENDING) {
                pending.add(c.getId());
            } else if (c.getDoctorStatus() == DoctorStatus.NEEDS_EVIDENCE) {
                needsEvidence.add(c.getId());
            } else if (c.getDoctorStatus() == DoctorStatus.APPROVED || c.getDoctorStatus() == DoctorStatus.EDITED) {
                approvedCount++;
            }
        }

        // Rule from ARCHITECTURE.md: no claim PENDING or NEEDS_EVIDENCE AND >=3 claims APPROVED|EDITED
        boolean ok = pending.isEmpty() && needsEvidence.isEmpty() && approvedCount >= 3;
        return new Gate1Result(ok, pending, needsEvidence, approvedCount);
    }

    public boolean canWrite(String paperId) {
        return checkGate1(paperId).ok();
    }
}
