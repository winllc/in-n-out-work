package com.winllc.innoutwork.repository;

import com.winllc.innoutwork.model.ApiToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiTokenRepository extends JpaRepository<ApiToken, Long> {

    Optional<ApiToken> findByTokenHash(String tokenHash);

    List<ApiToken> findAllByOrderByCreatedAtDesc();
}
