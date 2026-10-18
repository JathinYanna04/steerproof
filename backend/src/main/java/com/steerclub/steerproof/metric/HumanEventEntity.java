package com.steerclub.steerproof.metric;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "human_events")
public class HumanEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paper_id")
    private String paperId;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private String kind; // UPLOAD | CLAIM_REVIEW | ASSET_REVIEW | EDIT | EXPORT

    @Column(nullable = false)
    private Integer seconds;

    @Column(nullable = false)
    private Instant at = Instant.now();

    public HumanEventEntity() {}

    public HumanEventEntity(String paperId, String actor, String kind, Integer seconds) {
        this.paperId = paperId;
        this.actor = actor;
        this.kind = kind;
        this.seconds = seconds;
        this.at = Instant.now();
    }

    public Long getId() { return id; }
    public String getPaperId() { return paperId; }
    public String getActor() { return actor; }
    public String getKind() { return kind; }
    public Integer getSeconds() { return seconds; }
    public Instant getAt() { return at; }
}
