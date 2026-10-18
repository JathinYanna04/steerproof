package com.steerclub.steerproof.policy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steerclub.steerproof.claim.ClaimEntity;
import com.steerclub.steerproof.common.AssetType;
import com.steerclub.steerproof.common.DoctorStatus;
import com.steerclub.steerproof.common.FindingClass;
import com.steerclub.steerproof.paper.PaperEntity;
import com.steerclub.steerproof.pipeline.AssetContentEnricher;
import com.steerclub.steerproof.pipeline.MlServiceClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Offline evidence run: applies the enricher + PolicyChecker to writer output exported to JSON.
 * Runs only with -Dws1.evidence.input=<file> -Dws1.evidence.output=<file>; skipped otherwise.
 * Never touches a database. Claim review statuses are simulated as APPROVED in memory only
 * (except NEEDS_EVIDENCE), so the other rules can be evaluated before the human review.
 */
class WriterEvidenceHarness {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void run() throws Exception {
        String in = System.getProperty("ws1.evidence.input");
        String out = System.getProperty("ws1.evidence.output");
        assumeTrue(in != null && out != null, "evidence harness not requested");
        String illustrationError = System.getProperty("ws1.evidence.illustrationError");

        PolicyChecker checker = new PolicyChecker(om);
        checker.loadRules();
        AssetContentEnricher enricher = new AssetContentEnricher(Mockito.mock(MlServiceClient.class));

        List<Map<String, Object>> report = new ArrayList<>();
        for (JsonNode c : om.readTree(new File(in))) {
            PaperEntity paper = new PaperEntity();
            paper.setId(c.get("paperId").asText());
            paper.setAuthors(c.path("meta").path("authors").asText(null));
            paper.setYear(c.path("meta").has("year") ? c.path("meta").get("year").asInt() : null);
            paper.setJournal(c.path("meta").path("journal").asText(null));

            Map<String, ClaimEntity> claims = new HashMap<>();
            for (JsonNode cl : c.get("claims")) {
                ClaimEntity e = new ClaimEntity();
                e.setId(cl.get("claimId").asText());
                e.setFindingClass(FindingClass.valueOf(cl.path("findingClass").asText("PRIMARY")));
                e.setConsumerDraft(cl.path("consumerDraft").asText(null));
                e.setScientificClaim(cl.path("scientificClaim").asText(null));
                e.setQuote(cl.path("quote").asText(null));
                e.setEditedCopy(cl.path("editedCopy").isNull() ? null : cl.path("editedCopy").asText(null));
                boolean needsEvidence = "NEEDS_EVIDENCE".equals(cl.path("doctorStatus").asText());
                e.setDoctorStatus(needsEvidence ? DoctorStatus.NEEDS_EVIDENCE : DoctorStatus.APPROVED); // in-memory simulation only
                claims.put(e.getId(), e);
            }

            boolean newOutput = c.get("label").asText().contains("new writer output");
            IllustrationFindings findings = illustrationError != null ? IllustrationFindings.failed(illustrationError) : null;
            Map<String, Object> caseReport = new LinkedHashMap<>();
            caseReport.put("label", c.get("label").asText());
            caseReport.put("paperCitation", AssetContentEnricher.paperCitation(paper));
            Map<String, Object> assets = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = c.get("contents").fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                AssetType type = AssetType.valueOf(e.getKey());
                Map<String, Object> content = om.convertValue(e.getValue(), new TypeReference<Map<String, Object>>() {});
                Map<String, Object> a = new LinkedHashMap<>();
                if (!newOutput) {
                    // As stored before WS1 (no enrichment): what the old pipeline let through.
                    a.put("violationsAsStored", violations(checker.checkAsset("A", type.name(), om.valueToTree(content), claims, findings)));
                }
                enricher.enrich(type, content, paper, claims);
                a.put("violations", violations(checker.checkAsset("A", type.name(), om.valueToTree(content), claims, findings)));
                List<String> cits = new ArrayList<>();
                om.valueToTree(content).findValues("citation").forEach(n -> { if (!n.isNull()) cits.add(n.asText()); });
                a.put("citations", cits);
                a.put("enriched", content);
                assets.put(type.name(), a);
            }
            caseReport.put("assets", assets);
            report.add(caseReport);
        }
        om.writerWithDefaultPrettyPrinter().writeValue(new File(out), report);
    }

    private static List<String> violations(List<PolicyChecker.ViolationResult> v) {
        return v.stream().map(x -> x.severity() + " " + x.rule() + " @" + x.location() + ": " + x.detail()).toList();
    }
}
