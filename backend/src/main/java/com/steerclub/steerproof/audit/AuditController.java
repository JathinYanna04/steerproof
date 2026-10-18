package com.steerclub.steerproof.audit;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/audit")
    public List<AuditService.AuditLogEntry> getAudit(@RequestParam("entityId") String entityId) {
        return auditService.getAuditHistory(entityId);
    }
}
