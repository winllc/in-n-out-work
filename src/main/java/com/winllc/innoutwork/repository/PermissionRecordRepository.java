package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.model.PermissionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PermissionRecordRepository extends JpaRepository<PermissionRecord, Long>, PagingAndSortingRepository<PermissionRecord, Long> {

    List<PermissionRecord> findByUser_Dn(String dn);
    Optional<PermissionRecord> findFirstByGroupDnIgnoreCaseAndUser_DnIgnoreCase(String groupDn, String userDn);

    @Query("select p.groupDn from PermissionRecord p where p.user.id = :userId order by p.groupDn")
    List<String> findGroupDnsByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("delete from PermissionRecord p where p.user.id = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
