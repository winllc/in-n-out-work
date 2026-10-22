package com.winllc.innoutwork.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** User text put into a search filter as a value: every character with a meaning in a filter is escaped (RFC 4515). */
class LdapFilterEscapeTest {

    @Test
    void filterCharactersAreEscaped() {
        assertThat(LdapService.escapeLdapFilter("a*b")).isEqualTo("a\\2ab");
        assertThat(LdapService.escapeLdapFilter("(cn=x)")).isEqualTo("\\28cn=x\\29");
        assertThat(LdapService.escapeLdapFilter("back\\slash")).isEqualTo("back\\5cslash");
        assertThat(LdapService.escapeLdapFilter("nul\0")).isEqualTo("nul\\00");
    }

    @Test
    void anInjectionAttemptStaysOneValue() {
        assertThat(LdapService.escapeLdapFilter("*)(uid=*))(|(uid=*"))
                .isEqualTo("\\2a\\29\\28uid=\\2a\\29\\29\\28|\\28uid=\\2a");
    }

    @Test
    void ordinaryTextAndNothing() {
        assertThat(LdapService.escapeLdapFilter("Zoë O'Neil")).isEqualTo("Zoë O'Neil");
        assertThat(LdapService.escapeLdapFilter(null)).isEmpty();
        assertThat(LdapService.escapeLdapFilter("")).isEmpty();
    }
}
