package com.steerclub.steerproof.passage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PassageRepository extends JpaRepository<PassageEntity, String> {
    List<PassageEntity> findByPaperIdOrderByOrdAsc(String paperId);
    List<PassageEntity> findByPaperIdAndPageOrderByOrdAsc(String paperId, Integer page);
}
