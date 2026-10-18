package com.winllc.innoutwork.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LogSafeTest {

    @Test
    void lineBreaksCannotStartANewLogLine() {
        assertEquals("cn=a_ INFO forged", LogSafe.of("cn=a\n INFO forged"));
        assertEquals("cn=a__x", LogSafe.of("cn=a\r\nx"));
    }

    @Test
    void otherControlCharactersAreReplacedAndOrdinaryTextKept() {
        assertEquals("cn=a?b", LogSafe.of("cn=a\u001bb"));
        assertEquals("CN=Ana Müller,OU=Users,DC=example,DC=com", LogSafe.of("CN=Ana Müller,OU=Users,DC=example,DC=com"));
        assertNull(LogSafe.of(null));
    }
}
