package com.winllc.innoutwork.audit;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditOutputStateRepository extends JpaRepository<AuditOutputState, String> {
}
