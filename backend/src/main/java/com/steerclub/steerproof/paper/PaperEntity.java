package com.steerclub.steerproof.paper;

import com.steerclub.steerproof.common.PaperStatus;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "papers")
public class PaperEntity {

    @Id
    private String id;

    private String title;
    private String authors;
    private Integer year;
    private String journal;
    private String doi;

    @Column(name = "source_url")
    private String sourceUrl;

    @Column(name = "pdf_path", nullable = false)
    private String pdfPath;

    private String theme;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaperStatus status;

    @Column(name = "failed_stage")
    private String failedStage;

    private String error;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "batch_id")
    private String batchId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public PaperEntity() {}

    public PaperEntity(String id, String pdfPath, PaperStatus status) {
        this.id = id;
        this.pdfPath = pdfPath;
        this.status = status;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getAuthors() { return authors; }
    public void setAuthors(String authors) { this.authors = authors; }
    public Integer getYear() { return year; }
    public void setYear(Integer year) { this.year = year; }
    public String getJournal() { return journal; }
    public void setJournal(String journal) { this.journal = journal; }
    public String getDoi() { return doi; }
    public void setDoi(String doi) { this.doi = doi; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public String getPdfPath() { return pdfPath; }
    public void setPdfPath(String pdfPath) { this.pdfPath = pdfPath; }
    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }
    public PaperStatus getStatus() { return status; }
    public void setStatus(PaperStatus status) { this.status = status; this.updatedAt = Instant.now(); }
    public String getFailedStage() { return failedStage; }
    public void setFailedStage(String failedStage) { this.failedStage = failedStage; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
