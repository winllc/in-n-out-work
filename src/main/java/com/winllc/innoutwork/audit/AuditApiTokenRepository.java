package com.winllc.innoutwork.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AuditApiTokenRepository extends JpaRepository<AuditApiToken, Long> {

    Optional<AuditApiToken> findByTokenHash(String tokenHash);

    List<AuditApiToken> findAllByOrderByCreatedAtDesc();
}
