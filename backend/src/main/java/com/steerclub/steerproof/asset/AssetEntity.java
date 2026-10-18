package com.steerclub.steerproof.asset;

import com.steerclub.steerproof.common.AssetStatus;
import com.steerclub.steerproof.common.AssetType;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "assets")
public class AssetEntity {

    @Id
    private String id;

    @Column(name = "paper_id", nullable = false)
    private String paperId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssetType type;

    @Column(nullable = false)
    private String language = "en";

    @Column(nullable = false, columnDefinition = "jsonb")
    private String content;

    @Column(name = "render_paths", nullable = false, columnDefinition = "jsonb")
    private String renderPaths = "[]";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssetStatus status = AssetStatus.DRAFT;

    @Column(nullable = false)
    private Integer version = 1;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "scheduled_for")
    private LocalDate scheduledFor;

    public AssetEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPaperId() { return paperId; }
    public void setPaperId(String paperId) { this.paperId = paperId; }
    public AssetType getType() { return type; }
    public void setType(AssetType type) { this.type = type; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getRenderPaths() { return renderPaths; }
    public void setRenderPaths(String renderPaths) { this.renderPaths = renderPaths; }
    public AssetStatus getStatus() { return status; }
    public void setStatus(AssetStatus status) { this.status = status; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }
    public LocalDate getScheduledFor() { return scheduledFor; }
    public void setScheduledFor(LocalDate scheduledFor) { this.scheduledFor = scheduledFor; }
}
