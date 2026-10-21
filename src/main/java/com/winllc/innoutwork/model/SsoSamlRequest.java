package com.winllc.innoutwork.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** A SAML sign-in request awaiting the identity provider's answer (security.sso.SamlRequestStore). */
@Data
@NoArgsConstructor
@Entity
@Table(name = "sso_saml_request")
public class SsoSamlRequest {

    @Id
    @Column(length = 128)
    private String relayState;

    /** SHA-256 (hex) of the cookie the browser that started it holds. */
    @Column(length = 64, nullable = false)
    private String browserHash;

    /** Spring Security's AbstractSaml2AuthenticationRequest, serialized. */
    @Column(nullable = false)
    private byte[] request;

    /** Whether the session that started it had acknowledged the consent banner. */
    @Column(nullable = false)
    private boolean consented;

    @Column(nullable = false)
    private Instant expiresAt;
}
