package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.model.UserRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.PagingAndSortingRepository;

import java.util.Optional;

public interface UserRecordRepository extends JpaRepository<UserRecord, Long>, PagingAndSortingRepository<UserRecord, Long> {

    Optional<UserRecord> findByDnIgnoreCase(String dn);
}
