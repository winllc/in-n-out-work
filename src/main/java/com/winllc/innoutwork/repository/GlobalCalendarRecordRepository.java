package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.model.GlobalCalendarRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface GlobalCalendarRecordRepository extends JpaRepository<GlobalCalendarRecord, Long>, PagingAndSortingRepository<GlobalCalendarRecord, Long> {
    List<GlobalCalendarRecord> findByDate(LocalDate date);
    List<GlobalCalendarRecord> findByDateBetween(LocalDate from, LocalDate to);

}
