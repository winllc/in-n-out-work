package com.winllc.innoutwork.security;

import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.model.GroupRecord;
import com.winllc.innoutwork.repository.GroupRecordRepository;
import com.winllc.innoutwork.service.GroupService;
import com.winllc.innoutwork.service.LdapService;
import com.winllc.innoutwork.service.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Manager checks use the owner on the groups already read for the user, without looking each group up again. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PermissionEvaluatorTest {

    private static final String BOB = "cn=Bob,ou=Users,dc=winllc,dc=com";
    private static final String ALICE = "cn=Alice,ou=Users,dc=winllc,dc=com";
    private static final String CAROL = "cn=Carol,ou=Users,dc=winllc,dc=com";
    private static final String ENGINEERING = "cn=Engineering,ou=Groups,dc=winllc,dc=com";
    private static final String SALES = "cn=Sales,ou=Groups,dc=winllc,dc=com";

    @Mock private PermissionService permissionService;
    @Mock private LdapService ldapService;
    @Mock private GroupRecordRepository groupRecords;

    private PermissionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new PermissionEvaluator(permissionService, ldapService, new GroupService(ldapService, groupRecords));
        when(ldapService.findGroupsForUser(BOB)).thenReturn(List.of(
                new LdapGroup(SALES, "Sales"),
                group(ENGINEERING, ALICE)));
        when(groupRecords.findByGroupDnIgnoreCase(anyString())).thenReturn(Optional.empty());
    }

    private static LdapGroup group(String dn, String owner) {
        LdapGroup group = new LdapGroup(dn, new LdapDn(dn).getName());
        group.setManager(owner);
        return group;
    }

    @Test
    void theOwnerOfOneOfTheUsersGroupsManagesThem() {
        assertTrue(evaluator.userManagerCheck(BOB, new TestingAuthenticationToken(ALICE.toUpperCase(), null)));
        verify(ldapService, never()).lookupGroup(any());
    }

    @Test
    void anAlternateManagerRecordedForTheGroupManagesThem() {
        GroupRecord record = new GroupRecord();
        record.setGroupDn(SALES);
        record.addAltManager(CAROL);
        when(groupRecords.findByGroupDnIgnoreCase(SALES)).thenReturn(Optional.of(record));

        assertTrue(evaluator.userManagerCheck(BOB, new TestingAuthenticationToken(CAROL, null)));
    }

    @Test
    void someoneWhoManagesNoneOfTheUsersGroupsDoesNotManageThem() {
        assertFalse(evaluator.userManagerCheck(BOB, new TestingAuthenticationToken(CAROL, null)));
        verify(ldapService, never()).lookupGroup(any());
    }
}
