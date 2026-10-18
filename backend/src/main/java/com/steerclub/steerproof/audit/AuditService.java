package com.steerclub.steerproof.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "audit_log")
class AuditLogEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private String action;

    @Column(nullable = false)
    private String entity;

    @Column(name = "entity_id", nullable = false)
    private String entityId;

    @Column(name = "before", columnDefinition = "jsonb")
    private String before;

    @Column(name = "after", columnDefinition = "jsonb")
    private String after;

    @Column(nullable = false)
    private Instant at = Instant.now();

    public AuditLogEntity() {}

    public AuditLogEntity(String actor, String action, String entity, String entityId, String before, String after) {
        this.actor = actor;
        this.action = action;
        this.entity = entity;
        this.entityId = entityId;
        this.before = before;
        this.after = after;
        this.at = Instant.now();
    }

    public Long getId() { return id; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getEntity() { return entity; }
    public String getEntityId() { return entityId; }
    public String getBefore() { return before; }
    public String getAfter() { return after; }
    public Instant getAt() { return at; }
}

@Repository
interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {
    List<AuditLogEntity> findByEntityIdOrderByAtAsc(String entityId);
}

@Service
public class AuditService {
    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void log(String actor, String action, String entity, String entityId, Object before, Object after) {
        try {
            String beforeJson = before != null ? objectMapper.writeValueAsString(before) : null;
            String afterJson = after != null ? objectMapper.writeValueAsString(after) : null;
            AuditLogEntity log = new AuditLogEntity(actor, action, entity, entityId, beforeJson, afterJson);
            repository.save(log);
        } catch (Exception e) {
            throw new RuntimeException("Failed to write audit log", e);
        }
    }

    public List<AuditLogEntry> getAuditHistory(String entityId) {
        return repository.findByEntityIdOrderByAtAsc(entityId).stream()
                .map(e -> new AuditLogEntry(e.getId(), e.getActor(), e.getAction(), e.getEntity(), e.getEntityId(), e.getBefore(), e.getAfter(), e.getAt()))
                .toList();
    }

    public record AuditLogEntry(Long id, String actor, String action, String entity, String entityId, String before, String after, Instant at) {}
}
