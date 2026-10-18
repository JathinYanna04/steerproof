package com.steerclub.steerproof.claim;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ClaimController {

    private final ClaimService claimService;

    public ClaimController(ClaimService claimService) {
        this.claimService = claimService;
    }

    @PatchMapping("/claims/{claimId}")
    public ResponseEntity<ClaimService.ClaimReviewResponse> patchClaim(
            @PathVariable("claimId") String claimId,
            @RequestBody ClaimService.ClaimReviewRequest req
    ) {
        ClaimService.ClaimReviewResponse res = claimService.reviewClaim(claimId, req);
        return ResponseEntity.ok(res);
    }

    @GetMapping("/claims/{claimId}")
    public ResponseEntity<Map<String, Object>> getClaim(@PathVariable("claimId") String claimId) {
        return ResponseEntity.ok(claimService.getClaim(claimId));
    }

    @GetMapping("/papers/{paperId}/claims")
    public ResponseEntity<List<Map<String, Object>>> getClaimsByPaperId(@PathVariable("paperId") String paperId) {
        return ResponseEntity.ok(claimService.getClaimsByPaperId(paperId));
    }
}
