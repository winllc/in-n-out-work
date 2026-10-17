package com.winllc.innoutwork.security.x509;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/** Where a certificate says to ask about its revocation: its OCSP responder and CRL distribution points. */
final class CertificateExtensions {

    static final String AUTHORITY_INFO_ACCESS = "1.3.6.1.5.5.7.1.1";
    static final String CRL_DISTRIBUTION_POINTS = "2.5.29.31";
    static final String ACCESS_METHOD_OCSP = "1.3.6.1.5.5.7.48.1";

    private CertificateExtensions() {
    }

    /** The first http(s) OCSP responder in the AuthorityInfoAccess extension, or null. */
    static String ocspResponder(X509Certificate cert) {
        Der.Tlv aia = Der.single(Der.extension(cert.getExtensionValue(AUTHORITY_INFO_ACCESS)));
        if (aia == null) {
            return null;
        }
        // AuthorityInfoAccessSyntax ::= SEQUENCE OF AccessDescription { accessMethod OID, accessLocation GeneralName }
        for (Der.Tlv description : aia.children()) {
            List<Der.Tlv> parts = description.children();
            if (parts.size() == 2 && parts.get(0).tag() == Der.OID
                    && ACCESS_METHOD_OCSP.equals(Der.oid(parts.get(0).value())) && parts.get(1).isContext(6)) {
                String uri = parts.get(1).text();
                if (uri.startsWith("http://") || uri.startsWith("https://")) {
                    return uri;
                }
            }
        }
        return null;
    }

    /** The http(s) URIs of the CRLDistributionPoints extension (ldap: ones are skipped). */
    static List<String> crlDistributionPoints(X509Certificate cert) {
        List<String> uris = new ArrayList<>();
        Der.Tlv points = Der.single(Der.extension(cert.getExtensionValue(CRL_DISTRIBUTION_POINTS)));
        if (points == null) {
            return uris;
        }
        // SEQUENCE OF DistributionPoint { [0] distributionPoint { [0] fullName GeneralNames }, ... }
        for (Der.Tlv point : points.children()) {
            for (Der.Tlv field : point.children()) {
                if (!field.isContext(0)) {
                    continue;
                }
                for (Der.Tlv name : field.children()) {
                    if (!name.isContext(0)) {
                        continue;
                    }
                    for (Der.Tlv general : name.children()) {
                        if (general.isContext(6)) {
                            String uri = general.text();
                            if (uri.startsWith("http://") || uri.startsWith("https://")) {
                                uris.add(uri);
                            }
                        }
                    }
                }
            }
        }
        return uris;
    }
}
