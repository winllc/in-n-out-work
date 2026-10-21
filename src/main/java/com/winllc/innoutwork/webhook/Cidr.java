package com.winllc.innoutwork.webhook;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** An address range such as {@code 10.20.0.0/16} or {@code fd00:1::/64}. */
public record Cidr(byte[] network, int prefix) {

    /** @throws IllegalArgumentException for anything but an IP address literal and a prefix length */
    public static Cidr parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("empty CIDR");
        }
        String t = text.trim();
        int slash = t.indexOf('/');
        String address = slash < 0 ? t : t.substring(0, slash);
        if (!address.matches("[0-9a-fA-F:.]+")) {
            // A host name would be looked up here; only literals are ranges.
            throw new IllegalArgumentException("not an IP address: " + t);
        }
        byte[] bytes;
        try {
            bytes = InetAddress.getByName(address).getAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("not an IP address: " + t, e);
        }
        int bits = bytes.length * 8;
        int prefix;
        try {
            prefix = slash < 0 ? bits : Integer.parseInt(t.substring(slash + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a prefix length: " + t, e);
        }
        if (prefix < 0 || prefix > bits) {
            throw new IllegalArgumentException("prefix length out of range: " + t);
        }
        return new Cidr(bytes, prefix);
    }

    public boolean contains(InetAddress address) {
        byte[] a = address.getAddress();
        if (a.length != network.length) {
            return false;
        }
        int full = prefix / 8;
        for (int i = 0; i < full; i++) {
            if (a[i] != network[i]) {
                return false;
            }
        }
        int rest = prefix % 8;
        if (rest == 0) {
            return true;
        }
        int mask = (0xFF << (8 - rest)) & 0xFF;
        return (a[full] & mask) == (network[full] & mask);
    }
}
