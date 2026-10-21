package com.winllc.innoutwork.webhook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The SSRF guard: HTTPS only, and only public addresses unless an allow-listed range holds them. */
class WebhookAddressPolicyTest {

    private static WebhookAddressPolicy policy(List<String> allowed, Map<String, String> dns) {
        return new WebhookAddressPolicy(allowed.stream().map(Cidr::parse).toList(), host -> {
            String address = dns.get(host);
            if (address == null) {
                if (host.matches("[0-9a-fA-F:.]+")) {
                    return new InetAddress[]{InetAddress.getByName(host)};
                }
                throw new UnknownHostException(host);
            }
            String[] all = address.split(",");
            InetAddress[] out = new InetAddress[all.length];
            for (int i = 0; i < all.length; i++) {
                out[i] = InetAddress.getByName(all[i]);
            }
            return out;
        });
    }

    private static final WebhookAddressPolicy STRICT = policy(List.of(), Map.of(
            "hooks.example.com", "93.184.216.34",
            "mixed.example.com", "93.184.216.34,10.0.0.5",
            "internal.example.com", "10.20.1.2",
            "metadata.example.com", "169.254.169.254"));

    @ParameterizedTest
    @ValueSource(strings = {"http://hooks.example.com/x", "ftp://hooks.example.com/x", "hooks.example.com/x",
            "https://user:pw@hooks.example.com/x", "https:///nohost", "https://hooks.example.com/x#frag", "",
            "javascript:alert(1)"})
    void onlyPlainHttpsUrlsAreAccepted(String url) {
        assertThrows(WebhookRefusedException.class, () -> STRICT.checkUrl(url));
    }

    @Test
    void aPublicHttpsUrlIsAccepted() throws Exception {
        assertEquals("hooks.example.com", STRICT.checkUrl("https://hooks.example.com:8443/in?x=1").getHost());
        assertEquals(1, STRICT.resolve("hooks.example.com").size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.8.9.10", "::1", "0.0.0.0", "::", "10.1.2.3", "172.16.0.1",
            "192.168.1.1", "169.254.169.254", "169.254.10.10", "fe80::1", "fc00::1", "fd12:3456::1",
            "100.64.0.1", "198.18.0.1", "224.0.0.1", "255.255.255.255", "::ffff:127.0.0.1", "::ffff:10.0.0.1",
            "::ffff:169.254.169.254", "64:ff9b::7f00:1", "64:ff9b::a9fe:a9fe", "2002:7f00:1::", "2002:a9fe:a9fe::",
            "fd00:ec2::254", "100.100.100.200", "ff02::1"})
    void internalAndSpecialAddressesAreRefused(String address) throws Exception {
        assertNotNull(STRICT.refusal(InetAddress.getByName(address)), address);
        assertThrows(WebhookRefusedException.class, () -> STRICT.resolve(address));
    }

    /** Refused whatever the allow-list says, even one allowing every address. */
    @ParameterizedTest
    @ValueSource(strings = {
            "168.63.129.16",              // Azure wire server (metadata)
            "fd20:ce::254",               // metadata
            "::ffff:0:a00:1",             // SIIT, IPv4-translated (::ffff:0:0:0/96)
            "::ffff:0:5db8:d822",         // SIIT, even of a public address
            "2001:0:5ef5:79fb::1",        // Teredo
            "2001::1",                    // Teredo
            "64:ff9b:1::a00:1",           // local-use translation (64:ff9b:1::/48)
            "64:ff9b:1:ffff::1"})
    void metadataTranslatorsAndTunnelsAreRefusedWhateverTheAllowList(String address) throws Exception {
        WebhookAddressPolicy allowingAll = policy(List.of("0.0.0.0/0", "::/0"), Map.of());
        assertNotNull(allowingAll.refusal(InetAddress.getByName(address)), address);
    }

    /** IPv4 inside IPv6 is checked as the IPv4 address it carries. */
    @ParameterizedTest
    @ValueSource(strings = {
            "64:ff9b::a00:1",             // NAT64 of 10.0.0.1
            "64:ff9b::7f00:1",            // NAT64 of 127.0.0.1
            "2002:a00:1::1",              // 6to4 of 10.0.0.1
            "2001:db9::5efe:a00:1",       // ISATAP of 10.0.0.1
            "fe80::200:5efe:7f00:1",      // ISATAP of 127.0.0.1
            "2600:1::5efe:a9fe:a9fe",     // ISATAP of 169.254.169.254
            "::a00:1",                    // IPv4-compatible 10.0.0.1
            "::7f00:1"})                  // IPv4-compatible 127.0.0.1
    void embeddedIpv4AddressesGoThroughTheSameChecks(String address) throws Exception {
        assertNotNull(STRICT.refusal(InetAddress.getByName(address)), address);
    }

    @ParameterizedTest
    @ValueSource(strings = {"64:ff9b::5db8:d822", "2002:5db8:d822::1", "2600:1::5efe:5db8:d822"})
    void embeddedPublicIpv4IsAllowed(String address) throws Exception {
        assertNull(STRICT.refusal(InetAddress.getByName(address)), address);
    }

    @Test
    void aHostWithAnyInternalAddressIsRefused() {
        WebhookRefusedException e = assertThrows(WebhookRefusedException.class, () -> STRICT.resolve("mixed.example.com"));
        assertTrue(e.getMessage().contains("10.0.0.5"), e.getMessage());
        assertThrows(WebhookRefusedException.class, () -> STRICT.resolve("metadata.example.com"));
        assertThrows(WebhookRefusedException.class, () -> STRICT.resolve("nowhere.example.com"));
    }

    @Test
    void anAllowListedRangeIsAcceptedButMetadataNeverIs() throws Exception {
        WebhookAddressPolicy allowing = policy(List.of("10.20.0.0/16", "127.0.0.0/8", "::1/128", "169.254.0.0/16",
                "fd00::/8"), Map.of("internal.example.com", "10.20.1.2"));
        assertDoesNotThrow(() -> allowing.resolve("internal.example.com"));
        assertNull(allowing.refusal(InetAddress.getByName("127.0.0.1")));
        assertNull(allowing.refusal(InetAddress.getByName("::1")));
        assertNotNull(allowing.refusal(InetAddress.getByName("10.21.0.1")), "outside the range");
        assertNotNull(allowing.refusal(InetAddress.getByName("169.254.169.254")), "metadata, whatever the allow-list");
        assertNotNull(allowing.refusal(InetAddress.getByName("::ffff:169.254.169.254")));
        assertNotNull(allowing.refusal(InetAddress.getByName("fd00:ec2::254")));
        assertNotNull(allowing.refusal(InetAddress.getByName("224.0.0.1")), "multicast, whatever the allow-list");
    }

    @Test
    void cidrsParseAndMatch() throws Exception {
        assertTrue(Cidr.parse("10.0.0.0/8").contains(InetAddress.getByName("10.255.1.1")));
        assertTrue(!Cidr.parse("172.16.0.0/12").contains(InetAddress.getByName("172.32.0.1")));
        assertTrue(Cidr.parse("fd00:1::/64").contains(InetAddress.getByName("fd00:1::abcd")));
        assertTrue(Cidr.parse("192.0.2.7").contains(InetAddress.getByName("192.0.2.7")));
        for (String bad : List.of("", "10.0.0.0/33", "example.com/8", "10.0.0.0/x")) {
            assertThrows(IllegalArgumentException.class, () -> Cidr.parse(bad), bad);
        }
    }

    @Test
    void signaturesVerifyOnlyWithTheSecretAndWithinTolerance() {
        byte[] body = "{\"a\":1}".getBytes();
        String header = WebhookSignature.header("whsec_secret", 1_700_000_000L, body);
        assertTrue(WebhookSignature.verify("whsec_secret", header, body, 1_700_000_100L, 300));
        assertTrue(!WebhookSignature.verify("whsec_other", header, body, 1_700_000_100L, 300));
        assertTrue(!WebhookSignature.verify("whsec_secret", header, "{\"a\":2}".getBytes(), 1_700_000_100L, 300));
        assertTrue(!WebhookSignature.verify("whsec_secret", header, body, 1_700_001_000L, 300), "replayed later");
    }

    @Test
    void secretsAreSealedAndOnlyTheirKeyOpensThem() {
        SecretBox box = new SecretBox("k".repeat(32));
        String sealed = box.seal("whsec_abc");
        assertTrue(sealed.startsWith("v1:") && !sealed.contains("whsec_abc"));
        assertEquals("whsec_abc", box.open(sealed));
        assertTrue(!sealed.equals(box.seal("whsec_abc")), "a fresh IV each time");
        assertThrows(IllegalStateException.class, () -> new SecretBox("x".repeat(32)).open(sealed));
        assertThrows(IllegalArgumentException.class, () -> new SecretBox("short"));
    }
}
