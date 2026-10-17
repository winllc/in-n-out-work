package com.winllc.innoutwork.security.x509;

import org.springframework.security.core.AuthenticationException;

/**
 * A client certificate that does not sign in, and why, in the words the audit trail records: "certificate
 * revoked", "revocation unknown", "issuer not allowed", ... Audited where it is thrown, so the event publisher
 * does not map it to an event of its own.
 */
public class CertificateRefusedException extends AuthenticationException {

    public CertificateRefusedException(String reason) {
        super(reason);
    }
}
