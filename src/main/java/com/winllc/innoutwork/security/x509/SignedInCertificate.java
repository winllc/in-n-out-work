package com.winllc.innoutwork.security.x509;

import java.io.Serializable;

/**
 * What a certificate sign-in keeps of the certificate once it has signed someone in: its subject, issuer and
 * serial, as strings. The certificate itself is not kept in the security context, which is stored in the browser
 * session, in the database, whose reader accepts no JDK certificate implementation classes.
 */
public record SignedInCertificate(String subject, String issuer, String serial) implements Serializable {
}
