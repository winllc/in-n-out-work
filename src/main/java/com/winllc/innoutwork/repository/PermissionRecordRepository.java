package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.model.PermissionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PermissionRecordRepository extends JpaRepository<PermissionRecord, Long>, PagingAndSortingRepository<PermissionRecord, Long> {

    List<PermissionRecord> findByUser_Dn(String dn);
    Optional<PermissionRecord> findFirstByGroupDnIgnoreCaseAndUser_DnIgnoreCase(String groupDn, String userDn);
}
