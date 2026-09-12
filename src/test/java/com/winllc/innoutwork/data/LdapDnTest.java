package com.winllc.innoutwork.data;

import org.junit.jupiter.api.Test;

import javax.naming.ldap.LdapName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DNs travel as strings between the directory, the database, URLs and certificates, and are
 * compared and parsed along the way. These pin down the string handling in {@link LdapDn} and
 * {@link UserStatus} that every table and details link depends on.
 */
class LdapDnTest {

    private static final String ALICE = "cn=Alice Adams,ou=Users,dc=winllc,dc=com";
    private static final String JANE = "cn=Doe\\, Jane,ou=Users,dc=winllc,dc=com";

    @Test
    void separatorSpacesAreNormalisedAway() {
        assertEquals(ALICE, new LdapDn("cn=Alice Adams, ou=Users, dc=winllc, dc=com").dn());
    }

    @Test
    void spacesOnEitherSideOfASeparatorAreNormalisedAway() {
        assertEquals(ALICE, LdapDn.normalize("cn=Alice Adams , ou=Users ,dc=winllc, dc=com"));
    }

    /** /api/check/out is open to anonymous callers, whose principal name is not a DN. */
    @Test
    void normalisingSomethingThatIsNotADnLeavesItUnchanged() {
        assertEquals("anonymousUser", LdapDn.normalize("anonymousUser"));
        assertNull(LdapDn.normalize(null));
    }

    @Test
    void normalisingIsIdempotent() {
        assertEquals(JANE, LdapDn.normalize(LdapDn.normalize(JANE)));
    }

    @Test
    void equalityIgnoresCase() {
        LdapDn lower = new LdapDn(ALICE);
        LdapDn upper = new LdapDn(ALICE.toUpperCase());

        assertEquals(lower, upper);
        assertEquals(lower.hashCode(), upper.hashCode());
    }

    @Test
    void differentEntriesAreNotEqual() {
        assertNotEquals(new LdapDn(ALICE), new LdapDn("cn=Bob Barker,ou=Users,dc=winllc,dc=com"));
    }

    @Test
    void anInvalidDnIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LdapDn("not a dn"));
    }

    @Test
    void toStringIsTheDn() {
        assertEquals(ALICE, new LdapDn(ALICE).toString());
    }

    /**
     * Normalising must not change which entry is named. A ", " inside an escaped value is part of
     * the name, not a separator: dropping its space turns "Doe, Jane" into "Doe,Jane".
     */
    @Test
    void normalisingKeepsAnEscapedCommaInsideAValue() throws Exception {
        assertEquals(new LdapName(JANE), new LdapName(new LdapDn(JANE).dn()));
    }

    @Test
    void cnIsTheFirstValueIncludingSpaces() {
        assertEquals("Alice Adams", new LdapDn(ALICE).getCn());
    }

    @Test
    void cnIsReadRegardlessOfAttributeCase() {
        assertEquals("user3", new LdapDn("CN=user3,OU=Users,DC=winllc,DC=com").getCn());
    }

    @Test
    void cnOfADnNotStartingWithCnIsTheLeftmostRdn() {
        assertEquals("ou=Groups", new LdapDn("ou=Groups,dc=winllc,dc=com").getCn());
    }

    @Test
    void nameIsTheLeftmostRdnValue() {
        assertEquals("Engineering", new LdapDn("cn=Engineering,ou=Groups,dc=winllc,dc=com").getName());
        assertEquals("Groups", new LdapDn("ou=Groups,dc=winllc,dc=com").getName());
    }

    @Test
    void nameUnescapesTheValue() {
        assertEquals("Doe, Jane", new LdapDn(JANE).getName());
    }

    // --- UserStatus.getCn: the Name column of every user table ---------------------------------

    @Test
    void userStatusCnIsTheFirstValue() {
        assertEquals("Alice Adams", UserStatus.builder().dn(ALICE).build().getCn());
        assertEquals("user3", UserStatus.builder().dn("CN=user3,OU=Users,DC=winllc,DC=com").build().getCn());
    }

    @Test
    void userStatusCnIsBlankWithoutADn() {
        assertEquals("", new UserStatus().getCn());
    }

    /** A "Last, First" name must show whole, not cut at the escaped comma. */
    @Test
    void userStatusCnKeepsAnEscapedComma() {
        assertEquals("Doe, Jane", UserStatus.builder().dn(JANE).build().getCn());
    }
}
