package com.zello.zello.domain.activity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ActivityRepository extends JpaRepository<Activity, Long> {
    @EntityGraph(attributePaths = "actorAccount")
    @Query("select a from Activity a where a.projectId=:projectId and a.eventType like 'PROJECT_%' order by a.id desc")
    Page<Activity> membershipHistory(Long projectId, Pageable pageable);
}
