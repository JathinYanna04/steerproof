package com.steerclub.steerproof.pipeline;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "pipeline_runs")
public class PipelineRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paper_id")
    private String paperId;

    @Column(nullable = false)
    private String stage;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    private Boolean ok;

    @Column(name = "llm_calls")
    private Integer llmCalls = 0;

    @Column(name = "cache_hits")
    private Integer cacheHits = 0;

    private String error;

    private String model;

    public PipelineRunEntity() {}

    public PipelineRunEntity(String paperId, String stage, Instant startedAt) {
        this.paperId = paperId;
        this.stage = stage;
        this.startedAt = startedAt;
    }

    public Long getId() { return id; }
    public String getPaperId() { return paperId; }
    public void setPaperId(String paperId) { this.paperId = paperId; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public Boolean getOk() { return ok; }
    public void setOk(Boolean ok) { this.ok = ok; }
    public Integer getLlmCalls() { return llmCalls; }
    public void setLlmCalls(Integer llmCalls) { this.llmCalls = llmCalls; }
    public Integer getCacheHits() { return cacheHits; }
    public void setCacheHits(Integer cacheHits) { this.cacheHits = cacheHits; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
}
