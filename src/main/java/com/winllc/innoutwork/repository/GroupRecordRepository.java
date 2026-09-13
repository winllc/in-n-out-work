package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.model.GroupRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GroupRecordRepository extends JpaRepository<GroupRecord, Long>, PagingAndSortingRepository<GroupRecord, Long> {

    // lower() to use ix_group_records_group_dn_lower; the derived IgnoreCase query compares upper().
    @Query("select g from GroupRecord g where lower(g.groupDn) = lower(:dn)")
    Optional<GroupRecord> findByGroupDnIgnoreCase(@Param("dn") String dn);

}
