package com.steerclub.steerproof.asset;

import com.steerclub.steerproof.common.AssetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AssetRepository extends JpaRepository<AssetEntity, String> {
    List<AssetEntity> findByPaperIdOrderByIdAsc(String paperId);
    Optional<AssetEntity> findByPaperIdAndType(String paperId, AssetType type);
}
