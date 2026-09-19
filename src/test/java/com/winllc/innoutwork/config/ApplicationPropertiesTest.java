package com.winllc.innoutwork.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The user filter is written by hand in a config file and then combined into larger
 * filters, so the form it is stored in decides whether those come out valid.
 */
class ApplicationPropertiesTest {

    private final ApplicationProperties props = new ApplicationProperties();

    @Test
    void theFilterDefaultsToInetOrgPerson() {
        assertEquals("(objectclass=inetOrgPerson)", props.getUserLdapFilter());
    }

    @Test
    void aParenthesisedFilterIsKeptAsWritten() {
        props.setUserLdapFilter("(objectclass=posixAccount)");

        assertEquals("(objectclass=posixAccount)", props.getUserLdapFilter());
    }

    @Test
    void aBareFilterGainsTheParenthesesItNeeds() {
        // "(&objectclass=posixAccount(cn=*x*))" is what a bare value would produce once
        // combined, and JNDI rejects that as an unbalanced parenthesis.
        props.setUserLdapFilter("objectclass=posixAccount");

        assertEquals("(objectclass=posixAccount)", props.getUserLdapFilter());
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        props.setUserLdapFilter("  objectclass=posixAccount  ");

        assertEquals("(objectclass=posixAccount)", props.getUserLdapFilter());
    }

    @Test
    void aCompositeFilterIsLeftAlone() {
        String composite = "(&(objectclass=inetOrgPerson)(!(employeeType=CONTRACTOR)))";
        props.setUserLdapFilter(composite);

        assertEquals(composite, props.getUserLdapFilter());
    }

    @Test
    void aBlankFilterFallsBackToTheDefaultRatherThanMatchingNothing() {
        props.setUserLdapFilter("   ");
        assertEquals("(objectclass=inetOrgPerson)", props.getUserLdapFilter());

        props.setUserLdapFilter(null);
        assertEquals("(objectclass=inetOrgPerson)", props.getUserLdapFilter());
    }
}
