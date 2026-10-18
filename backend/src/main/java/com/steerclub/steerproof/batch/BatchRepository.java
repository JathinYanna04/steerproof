package com.steerclub.steerproof.batch;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BatchRepository extends JpaRepository<BatchEntity, String> {
    List<BatchEntity> findAllByOrderByCreatedAtDesc();
}
