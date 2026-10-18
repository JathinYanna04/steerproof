package com.steerclub.steerproof.asset;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AssetClaimRepository extends JpaRepository<AssetClaimEntity, AssetClaimId> {
    List<AssetClaimEntity> findByAssetId(String assetId);
    List<AssetClaimEntity> findByClaimId(String claimId);
    @org.springframework.transaction.annotation.Transactional
    void deleteByAssetId(String assetId);
}
