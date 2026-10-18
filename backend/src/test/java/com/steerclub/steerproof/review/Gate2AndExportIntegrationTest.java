package com.steerclub.steerproof.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steerclub.steerproof.asset.AssetClaimEntity;
import com.steerclub.steerproof.asset.AssetClaimRepository;
import com.steerclub.steerproof.asset.AssetController;
import com.steerclub.steerproof.asset.AssetEntity;
import com.steerclub.steerproof.asset.AssetRepository;
import com.steerclub.steerproof.claim.ClaimEntity;
import com.steerclub.steerproof.claim.ClaimRepository;
import com.steerclub.steerproof.claim.ClaimService;
import com.steerclub.steerproof.common.*;
import com.steerclub.steerproof.export.ExportController;
import com.steerclub.steerproof.paper.PaperEntity;
import com.steerclub.steerproof.paper.PaperRepository;
import com.steerclub.steerproof.pipeline.MlServiceClient;
import com.steerclub.steerproof.pipeline.RenderServiceClient;
import com.steerclub.steerproof.policy.PolicyChecker;
import com.steerclub.steerproof.policy.PolicyViolationEntity;
import com.steerclub.steerproof.policy.PolicyViolationRepository;
import com.steerclub.steerproof.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest
public class Gate2AndExportIntegrationTest {

    @Autowired
    private AssetController assetController;

    @Autowired
    private ExportController exportController;

    @Autowired
    private ClaimService claimService;

    @Autowired
    private PaperRepository paperRepository;

    @Autowired
    private ClaimRepository claimRepository;

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private AssetClaimRepository assetClaimRepository;

    @Autowired
    private PolicyViolationRepository policyViolationRepository;

    @Autowired
    private PolicyChecker policyChecker;

    @Autowired
    private StorageService storageService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RenderServiceClient renderServiceClient;

    @MockBean
    private MlServiceClient mlServiceClient;

    private static int counter = (int) (System.currentTimeMillis() % 100000);

    @BeforeEach
    void setupMocks() {
        Mockito.when(renderServiceClient.renderCarousel(anyString(), anyList(), anyString(), anyBoolean()))
                .thenReturn(new RenderServiceClient.RenderResult(List.of("renders/test/carousel/1.png"), List.of()));

        Mockito.when(renderServiceClient.renderStatic(anyString(), anyMap(), anyString(), anyBoolean()))
                .thenReturn(new RenderServiceClient.RenderResult(List.of("renders/test/static.png"), List.of()));

        Mockito.when(renderServiceClient.renderReel(anyString(), anyList(), anyString(), anyBoolean()))
                .thenReturn(Map.of("path", "renders/test/reel.mp4", "durationSec", 30));
    }

    @Test
    void testFullGate2AndExportSequence() throws Exception {
        String paperId = "P-G2-" + (++counter);
        PaperEntity paper = new PaperEntity(paperId, "papers/" + paperId + ".pdf", PaperStatus.AWAITING_ASSET_REVIEW);
        paper.setTitle("Exploratory Trial on Metabolic Regulation");
        paper.setAuthors("Wilding et al.");
        paper.setYear(2022);
        paper.setJournal("Diabetes Obes Metab");
        paperRepository.save(paper);

        // 1. Create a claim with findingClass = EXPLORATORY, wrongly approved
        String claimId = "C-" + paperId + "-001";
        ClaimEntity claim = new ClaimEntity();
        claim.setId(claimId);
        claim.setPaperId(paperId);
        claim.setFindingClass(FindingClass.EXPLORATORY);
        claim.setDoctorStatus(DoctorStatus.APPROVED);
        claim.setQuote("In an exploratory extension trial, weight regain was observed.");
        claim.setScientificClaim("Weight regain occurred in an exploratory trial.");
        claim.setConsumerDraft("Weight regain was observed in exploratory findings.");
        claimRepository.save(claim);

        // 2. Build CAROUSEL asset with certainty language triggering CERTAINTY_ON_EXPLORATORY warning
        String carAssetId = "A-" + paperId + "-CAR";
        ObjectNode carouselContent = objectMapper.createObjectNode();
        ArrayNode slides = carouselContent.putArray("slides");
        ObjectNode s1 = slides.addObject();
        s1.put("n", 1);
        s1.put("kind", "claim");
        s1.put("headline", "Weight always comes back");
        s1.put("body", "Studies prove that you will regain all lost weight.");
        s1.putArray("claimIds").add(claimId);

        AssetEntity carAsset = new AssetEntity();
        carAsset.setId(carAssetId);
        carAsset.setPaperId(paperId);
        carAsset.setType(AssetType.CAROUSEL);
        carAsset.setContent(objectMapper.writeValueAsString(carouselContent));
        carAsset.setStatus(AssetStatus.RENDERED);
        carAsset.setVersion(1);
        assetRepository.save(carAsset);

        assetClaimRepository.save(new AssetClaimEntity(carAssetId, claimId, "slide:1"));

        // Create policy violation for CERTAINTY_ON_EXPLORATORY
        PolicyViolationEntity violation = new PolicyViolationEntity(
                carAssetId, claimId, "slide:1", "CERTAINTY_ON_EXPLORATORY", Severity.WARNING, "Definitive language on exploratory finding"
        );
        policyViolationRepository.save(violation);

        // Also create STATIC, REEL, CAPTION in RENDERED status
        for (AssetType t : List.of(AssetType.STATIC, AssetType.REEL, AssetType.CAPTION)) {
            String aid = "A-" + paperId + "-" + t.name().substring(0, 3);
            AssetEntity a = new AssetEntity();
            a.setId(aid);
            a.setPaperId(paperId);
            a.setType(t);
            a.setContent("{}");
            a.setStatus(AssetStatus.APPROVED); // Pre-approved so we focus on carousel
            a.setVersion(1);
            assetRepository.save(a);
        }

        // 3. Try to approve CAROUSEL without overrideWarnings -> Should fail with 422 ASSET_BLOCKED
        ResponseEntity<?> approveFail = assetController.approveAsset(
                carAssetId,
                new AssetController.AssetApprovalRequest("Reviewer", 10, false)
        );
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, approveFail.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> failBody = (Map<String, Object>) approveFail.getBody();
        assertNotNull(failBody);
        assertEquals("ASSET_BLOCKED", failBody.get("error"));

        // 4. Doctor rejects the bad claim -> asset becomes STALE!
        claimService.reviewClaim(claimId, new ClaimService.ClaimReviewRequest(
                "REJECT", "Reviewer", null, null, "Exploratory claim is not clinically definitive", 15
        ));

        AssetEntity staleAsset = assetRepository.findById(carAssetId).orElseThrow();
        assertEquals(AssetStatus.STALE, staleAsset.getStatus(), "Asset linked to rejected claim must transition to STALE");

        // 5. Try to export paper while asset is STALE -> Should fail with 422 EXPORT_BLOCKED
        ResponseEntity<?> exportFail = exportController.exportPaper(paperId);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exportFail.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> exportFailBody = (Map<String, Object>) exportFail.getBody();
        assertNotNull(exportFailBody);
        assertEquals("EXPORT_BLOCKED", exportFailBody.get("error"));

        // 6. Regenerate asset (simulate writer producing clean version without rejected claim)
        policyViolationRepository.deleteByAssetId(carAssetId);
        assetClaimRepository.deleteByAssetId(carAssetId);

        ObjectNode cleanCarousel = objectMapper.createObjectNode();
        ArrayNode cleanSlides = cleanCarousel.putArray("slides");
        ObjectNode cs1 = cleanSlides.addObject();
        cs1.put("n", 1);
        cs1.put("kind", "brand");
        cs1.put("headline", "Metabolic care");
        cs1.put("body", "Calibrated for everyday life.");
        cs1.putArray("claimIds");

        staleAsset.setContent(objectMapper.writeValueAsString(cleanCarousel));
        staleAsset.setStatus(AssetStatus.RENDERED);
        assetRepository.save(staleAsset);

        // 7. Approve the regenerated asset
        ResponseEntity<?> approveOk = assetController.approveAsset(
                carAssetId,
                new AssetController.AssetApprovalRequest("Jathin Reddy Yanna (non-clinician reviewer)", 12, false)
        );
        assertEquals(HttpStatus.OK, approveOk.getStatusCode());
        AssetEntity approvedCar = assetRepository.findById(carAssetId).orElseThrow();
        assertEquals(AssetStatus.APPROVED, approvedCar.getStatus());
        assertEquals("Jathin Reddy Yanna (non-clinician reviewer)", approvedCar.getApprovedBy());

        // 8. Call export -> Should succeed with 200 OK!
        ResponseEntity<?> exportOk = exportController.exportPaper(paperId);
        assertEquals(HttpStatus.OK, exportOk.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> exportOkBody = (Map<String, Object>) exportOk.getBody();
        assertNotNull(exportOkBody);
        assertEquals("EXPORTED", exportOkBody.get("status"));
        assertEquals("/files/exports/" + paperId + ".zip", exportOkBody.get("zipUrl"));

        // 9. Verify zip file exists on disk and has entries
        Path zipPath = storageService.getRootPath().resolve("exports").resolve(paperId + ".zip");
        assertTrue(Files.exists(zipPath), "Export zip archive must exist on disk");

        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            assertNotNull(zipFile.getEntry("README.txt"), "README.txt must be inside export zip");
            assertNotNull(zipFile.getEntry("sources.md"), "sources.md must be inside export zip");
            assertNotNull(zipFile.getEntry("review_report.json"), "review_report.json must be inside export zip");
            assertNotNull(zipFile.getEntry("caption.txt"), "caption.txt must be inside export zip");
        }

        // Verify paper status in DB
        PaperEntity exportedPaper = paperRepository.findById(paperId).orElseThrow();
        assertEquals(PaperStatus.EXPORTED, exportedPaper.getStatus());
    }
}
