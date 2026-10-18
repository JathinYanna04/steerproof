package com.steerclub.steerproof.metric;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HumanEventRepository extends JpaRepository<HumanEventEntity, Long> {
    List<HumanEventEntity> findByPaperIdOrderByAtAsc(String paperId);
}
