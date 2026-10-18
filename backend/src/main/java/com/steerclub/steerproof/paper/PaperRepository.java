package com.steerclub.steerproof.paper;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaperRepository extends JpaRepository<PaperEntity, String> {
    List<PaperEntity> findAllByOrderByCreatedAtDesc();
}
