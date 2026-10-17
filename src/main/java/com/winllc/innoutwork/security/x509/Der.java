package com.winllc.innoutwork.security.x509;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Just enough DER for what the JDK parses but keeps to itself: the UPN and UUID out of a subject alternative name,
 * the OCSP and CRL addresses out of a certificate's extensions, the key hash an OCSP request names an issuer by,
 * and the OCSP request itself. Definite lengths only, which is all DER has; anything malformed reads as empty.
 */
final class Der {

    static final int INTEGER = 0x02;
    static final int BIT_STRING = 0x03;
    static final int OCTET_STRING = 0x04;
    static final int NULL = 0x05;
    static final int OID = 0x06;
    static final int SEQUENCE = 0x30;

    private Der() {
    }

    /** One tag-length-value. */
    record Tlv(int tag, byte[] value) {

        boolean constructed() {
            return (tag & 0x20) != 0;
        }

        /** A context-specific tag {@code [n]}, constructed or not. */
        boolean isContext(int n) {
            return (tag & 0xC0) == 0x80 && (tag & 0x1F) == n;
        }

        List<Tlv> children() {
            return parse(value);
        }

        String text() {
            return new String(value, StandardCharsets.UTF_8);
        }
    }

    /** The values at the top level of {@code der}; empty (or cut short) where it does not parse. */
    static List<Tlv> parse(byte[] der) {
        List<Tlv> values = new ArrayList<>();
        int at = 0;
        while (der != null && at < der.length) {
            int tag = der[at++] & 0xFF;
            if ((tag & 0x1F) == 0x1F || at >= der.length) {
                return values; // multi-byte tags do not occur in what is read here
            }
            int length = der[at++] & 0xFF;
            if (length > 0x7F) {
                int bytes = length & 0x7F;
                if (bytes == 0 || bytes > 4 || at + bytes > der.length) {
                    return values;
                }
                length = 0;
                for (int i = 0; i < bytes; i++) {
                    length = (length << 8) | (der[at++] & 0xFF);
                }
                if (length < 0) {
                    return values;
                }
            }
            if (at + length > der.length) {
                return values;
            }
            byte[] value = new byte[length];
            System.arraycopy(der, at, value, 0, length);
            values.add(new Tlv(tag, value));
            at += length;
        }
        return values;
    }

    /** The one value {@code der} holds, or null. */
    static Tlv single(byte[] der) {
        List<Tlv> values = parse(der);
        return values.size() == 1 ? values.getFirst() : null;
    }

    /** An OBJECT IDENTIFIER's dotted form. */
    static String oid(byte[] value) {
        if (value.length == 0) {
            return "";
        }
        StringBuilder oid = new StringBuilder();
        int first = value[0] & 0xFF;
        oid.append(Math.min(first / 40, 2)).append('.').append(first - 40 * Math.min(first / 40, 2));
        long part = 0;
        for (int i = 1; i < value.length; i++) {
            part = (part << 7) | (value[i] & 0x7F);
            if ((value[i] & 0x80) == 0) {
                oid.append('.').append(part);
                part = 0;
            }
        }
        return oid.toString();
    }

    /**
     * An extension's value as {@code X509Certificate.getExtensionValue} returns it (an OCTET STRING wrapping the
     * DER), unwrapped; null when absent or malformed.
     */
    static byte[] extension(byte[] wrapped) {
        Tlv octets = wrapped == null ? null : single(wrapped);
        return octets != null && octets.tag() == OCTET_STRING ? octets.value() : null;
    }

    // --- writing ----------------------------------------------------------------------------------------------

    static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length + 6);
        out.write(tag);
        int length = value.length;
        if (length < 0x80) {
            out.write(length);
        } else if (length < 0x100) {
            out.write(0x81);
            out.write(length);
        } else if (length < 0x10000) {
            out.write(0x82);
            out.write(length >> 8);
            out.write(length);
        } else {
            out.write(0x83);
            out.write(length >> 16);
            out.write(length >> 8);
            out.write(length);
        }
        out.writeBytes(value);
        return out.toByteArray();
    }

    static byte[] sequence(byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            body.writeBytes(part);
        }
        return tlv(SEQUENCE, body.toByteArray());
    }

    static byte[] integer(BigInteger value) {
        return tlv(INTEGER, value.toByteArray());
    }

    static byte[] octets(byte[] value) {
        return tlv(OCTET_STRING, value);
    }

    /** An OBJECT IDENTIFIER from its dotted form. */
    static byte[] oid(String dotted) {
        String[] parts = dotted.split("\\.");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]));
        for (int i = 2; i < parts.length; i++) {
            long part = Long.parseLong(parts[i]);
            byte[] encoded = new byte[10];
            int n = 0;
            do {
                encoded[n++] = (byte) (part & 0x7F);
                part >>>= 7;
            } while (part != 0);
            for (int j = n - 1; j >= 0; j--) {
                body.write(encoded[j] | (j > 0 ? 0x80 : 0));
            }
        }
        return tlv(OID, body.toByteArray());
    }

    static byte[] nullValue() {
        return new byte[]{NULL, 0};
    }
}
