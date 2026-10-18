package com.steerclub.steerproof.claim;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClaimRepository extends JpaRepository<ClaimEntity, String> {
    List<ClaimEntity> findByPaperIdOrderByIdAsc(String paperId);
}
