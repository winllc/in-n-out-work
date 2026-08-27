package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.model.GroupRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GroupRecordRepository extends JpaRepository<GroupRecord, Long>, PagingAndSortingRepository<GroupRecord, Long> {

    Optional<GroupRecord> findByGroupDnIgnoreCase(String dn);

}
