package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.model.OrgParseRuleRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrgParseRuleRecordRepository extends JpaRepository<OrgParseRuleRecord, Long>, PagingAndSortingRepository<OrgParseRuleRecord, Long> {

    Optional<OrgParseRuleRecord> findByOrgNameEqualsIgnoreCase(String dn);

}
