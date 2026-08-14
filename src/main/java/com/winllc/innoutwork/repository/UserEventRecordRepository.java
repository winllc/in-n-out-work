package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.model.UserEventRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserEventRecordRepository  extends JpaRepository<UserEventRecord, Long>, PagingAndSortingRepository<UserEventRecord, Long> {
    List<UserEventRecord> findByDnIgnoreCaseAndDateBetween(String dn, LocalDate start, LocalDate end);
    List<UserEventRecord> findByDnIgnoreCaseAndDate(String dn, LocalDate date);
    Optional<UserEventRecord> findByDnIgnoreCaseAndDateAndStatusEquals(String dn, LocalDate date, UserStatusEnum status);
}
