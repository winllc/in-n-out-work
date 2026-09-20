package com.winllc.innoutwork.service;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.data.LdapGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.query.LdapQuery;
import org.springframework.ldap.support.LdapNameBuilder;

import javax.naming.Name;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * What the recursive group walk does when the directory fails part way through.
 *
 * <p>It used to return the node it had built so far. That is indistinguishable from a
 * real leaf group, so a single referral or timeout was cached as a complete tree and
 * served childless until the entry expired, with nothing able to evict it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LdapServiceGroupTreeTest {

    private static final String ROOT = "ou=groups,dc=example,dc=com";
    private static final String CHILD = "cn=engineering,ou=groups,dc=example,dc=com";

    @Mock
    private LdapTemplate ldapTemplate;

    private LdapService ldapService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ldapService = new LdapService(ldapTemplate, new ApplicationProperties());

        // Every DN in these tests resolves, and resolving one is the same call that reads its
        // members; only the child enumeration below is made to fail.
        when(ldapTemplate.lookup(anyString(), any(String[].class), any(AttributesMapper.class)))
                .thenReturn(List.of());
    }

    private Name name(String dn) {
        return LdapNameBuilder.newInstance(dn).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailedChildEnumerationIsNotPassedOffAsALeafGroup() {
        when(ldapTemplate.search(any(LdapQuery.class), any(ContextMapper.class)))
                .thenThrow(new RuntimeException("connection reset"));

        GroupTreeIncompleteException thrown = assertThrows(GroupTreeIncompleteException.class,
                () -> ldapService.buildGroupRecursiveInternal(ROOT));

        // The node is still handed over so the request can render something; what must not
        // happen is returning it normally, where the caller would cache it.
        assertNotNull(thrown.getPartial());
        assertEquals(ROOT, thrown.getPartial().getDn());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBranchThatFailsDoesNotCostTheBranchesThatSucceeded() {
        // Root enumerates one child; walking that child is what fails.
        when(ldapTemplate.search(any(LdapQuery.class), any(ContextMapper.class)))
                .thenReturn(List.of(name(CHILD)))
                .thenThrow(new RuntimeException("connection reset"));

        GroupTreeIncompleteException thrown = assertThrows(GroupTreeIncompleteException.class,
                () -> ldapService.buildGroupRecursiveInternal(ROOT));

        LdapGroup partial = thrown.getPartial();
        assertNotNull(partial);
        assertEquals(1, partial.getChildren().size(), "the branch that was built should survive");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCompleteWalkReturnsNormally() {
        when(ldapTemplate.search(any(LdapQuery.class), any(ContextMapper.class)))
                .thenReturn(List.of());

        LdapGroup group = ldapService.buildGroupRecursiveInternal(ROOT);

        assertNotNull(group);
        assertEquals(ROOT, group.getDn());
        assertEquals(0, group.getChildren().size());
    }
}
