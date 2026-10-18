package com.steerclub.steerproof.policy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PolicyViolationRepository extends JpaRepository<PolicyViolationEntity, Long> {
    List<PolicyViolationEntity> findByAssetId(String assetId);
    void deleteByAssetId(String assetId);
}
