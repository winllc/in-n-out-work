package com.winllc.innoutwork.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

/** One canonical spelling per address, so a logoff's address compares equal to its logon's. */
class ClientAddressTest {

    @Test
    void ipv4IsKeptAsItIs() {
        assertEquals("10.0.0.1", ClientAddress.normalize("10.0.0.1"));
        assertEquals("10.0.0.1", ClientAddress.normalize(" 10.0.0.1 "));
    }

    @Test
    void ipv4MappedIntoIpv6IsTheIpv4Address() {
        assertEquals("10.0.0.1", ClientAddress.normalize("::ffff:10.0.0.1"));
        assertEquals("10.0.0.1", ClientAddress.normalize("[::FFFF:10.0.0.1]"));
    }

    @Test
    void ipv6IsWrittenOutInFullAndLowerCase() {
        assertEquals("0:0:0:0:0:0:0:1", ClientAddress.normalize("::1"));
        assertEquals(ClientAddress.normalize("2001:DB8::1"), ClientAddress.normalize("2001:db8:0:0:0:0:0:1"));
    }

    /** Never looked up: a name or a malformed literal is compared as sent. */
    @Test
    void anythingElseIsComparedAsSent() {
        assertEquals("workstation-7", ClientAddress.normalize("Workstation-7"));
        assertEquals("cafe", ClientAddress.normalize("cafe"));
        assertEquals("1:2:3:4:5:6:7:8:9", ClientAddress.normalize("1:2:3:4:5:6:7:8:9"));
    }

    @Test
    void noAddressIsNull() {
        assertNull(ClientAddress.normalize(null));
        assertNull(ClientAddress.normalize(" "));
    }

    @Test
    void theRequestsAddressIsItsRemoteAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("::ffff:192.0.2.1");

        assertEquals("192.0.2.1", ClientAddress.of(request));
    }
}
