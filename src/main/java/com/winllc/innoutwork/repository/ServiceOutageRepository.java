package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.model.ServiceOutage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ServiceOutageRepository extends JpaRepository<ServiceOutage, Long> {

    /** Outages that ended after {@code after}, oldest first. */
    List<ServiceOutage> findByDownUntilAfterOrderByDownFromAsc(Instant after);

    @Modifying
    @Query("delete from ServiceOutage o where o.downUntil < :before")
    int deleteEndedBefore(@Param("before") Instant before);
}
