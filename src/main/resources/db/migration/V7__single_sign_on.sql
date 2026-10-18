-- =====================================================================================================
-- V7: SAML single sign-on that works with several replicas (RR-17).
--
-- sso_saml_request: the sign-in requests ReadyRoom sent to the SAML identity provider, until its answer comes
-- back (ten minutes at most). The answer is a cross-site POST, on which browsers leave out the SameSite=Lax
-- session cookie, so the request is found by the RelayState the IdP returns, on whichever replica receives it,
-- and is tied to the browser that started it by a cookie of its own (only its SHA-256 is stored). Each request
-- is used once: the response must answer it (InResponseTo), and IdP-initiated sign-in is refused.
--
-- sso_assertion_seen: the ids of assertions already used, until they expire, so one cannot be replayed.
-- =====================================================================================================

CREATE TABLE sso_saml_request (
    relay_state  varchar(128) PRIMARY KEY,
    browser_hash varchar(64)  NOT NULL,
    request      bytea        NOT NULL,
    consented    boolean      NOT NULL,
    expires_at   timestamp(6) with time zone NOT NULL
);
CREATE INDEX ix_sso_saml_request_expires ON sso_saml_request (expires_at);

CREATE TABLE sso_assertion_seen (
    assertion_id varchar(256) PRIMARY KEY,
    expires_at   timestamp(6) with time zone NOT NULL
);
CREATE INDEX ix_sso_assertion_seen_expires ON sso_assertion_seen (expires_at);
