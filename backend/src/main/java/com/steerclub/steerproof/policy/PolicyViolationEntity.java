package com.steerclub.steerproof.policy;

import com.steerclub.steerproof.common.Severity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "policy_violations")
public class PolicyViolationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_id")
    private String assetId;

    @Column(name = "claim_id")
    private String claimId;

    private String location;

    @Column(nullable = false)
    private String rule;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Column(nullable = false, columnDefinition = "text")
    private String detail;

    @Column(nullable = false)
    private Boolean resolved = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public PolicyViolationEntity() {}

    public PolicyViolationEntity(String assetId, String claimId, String location, String rule, Severity severity, String detail) {
        this.assetId = assetId;
        this.claimId = claimId;
        this.location = location;
        this.rule = rule;
        this.severity = severity;
        this.detail = detail;
        this.resolved = false;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getAssetId() { return assetId; }
    public void setAssetId(String assetId) { this.assetId = assetId; }
    public String getClaimId() { return claimId; }
    public void setClaimId(String claimId) { this.claimId = claimId; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getRule() { return rule; }
    public void setRule(String rule) { this.rule = rule; }
    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Boolean getResolved() { return resolved; }
    public void setResolved(Boolean resolved) { this.resolved = resolved; }
    public Instant getCreatedAt() { return createdAt; }
}
