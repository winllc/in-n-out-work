package com.winllc.innoutwork.service;

import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.model.PermissionRecord;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.PermissionRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Granting and revoking group permissions writes the permission rows themselves, never touching the user's
 * lazy permissions list, so it works without an open persistence session.
 */
class PermissionServiceTest {

    private static final LdapDn USER = new LdapDn("cn=Bob Barker,ou=Users,dc=winllc,dc=com");
    private static final LdapDn GROUP = new LdapDn("cn=Engineering,ou=Groups,dc=winllc,dc=com");

    private UserRecordRepository users;
    private PermissionRecordRepository permissions;
    private PermissionService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRecordRepository.class);
        permissions = mock(PermissionRecordRepository.class);
        when(users.save(any(UserRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new PermissionService(users, permissions, mock(LdapService.class));
    }

    @Test
    void grantingSavesAPermissionForTheExistingUser() {
        UserRecord bob = UserRecord.builder().id(3L).dn(USER.dn()).build();
        when(users.findByDnIgnoreCase(USER.dn())).thenReturn(Optional.of(bob));

        service.addGroupToUser(GROUP, USER);

        ArgumentCaptor<PermissionRecord> saved = ArgumentCaptor.forClass(PermissionRecord.class);
        verify(permissions).save(saved.capture());
        assertSame(bob, saved.getValue().getUser());
        assertEquals(GROUP.dn(), saved.getValue().getGroupDn());
        verify(users, never()).save(any());
    }

    @Test
    void grantingToSomeoneWithNoRecordCreatesOneFirst() {
        when(users.findByDnIgnoreCase(anyString())).thenReturn(Optional.empty());

        service.addGroupToUser(GROUP, USER);

        ArgumentCaptor<PermissionRecord> saved = ArgumentCaptor.forClass(PermissionRecord.class);
        verify(permissions).save(saved.capture());
        assertEquals(USER.dn(), saved.getValue().getUser().getDn());
    }

    @Test
    void revokingDeletesThePermission() {
        PermissionRecord grant = PermissionRecord.builder().id(9L).groupDn(GROUP.dn()).build();
        when(permissions.findFirstByGroupDnIgnoreCaseAndUser_DnIgnoreCase(GROUP.dn(), USER.dn())).thenReturn(Optional.of(grant));

        service.removeGroupFromUser(GROUP, USER);

        verify(permissions).delete(grant);
        verify(users, never()).save(any());
    }

    @Test
    void revokingSomethingNeverGrantedDoesNothing() {
        when(permissions.findFirstByGroupDnIgnoreCaseAndUser_DnIgnoreCase(anyString(), anyString())).thenReturn(Optional.empty());

        service.removeGroupFromUser(GROUP, USER);

        verify(permissions, never()).delete(any());
    }

    /** Previously a permission compared its user, whose equals compared the permissions list, and so on. */
    @Test
    void aPermissionAndItsUserCanBeComparedAndPrintedWithoutRecursing() {
        UserRecord bob = UserRecord.builder().id(3L).dn(USER.dn()).build();
        PermissionRecord grant = PermissionRecord.builder().id(9L).user(bob).groupDn(GROUP.dn()).build();
        bob.setPermissions(new java.util.ArrayList<>(java.util.List.of(grant)));

        assertEquals(grant, PermissionRecord.builder().id(9L).user(bob).groupDn(GROUP.dn()).build());
        assertEquals(bob.hashCode(), bob.hashCode());
        assertEquals(true, bob.toString().contains(USER.dn()));
        assertEquals(true, grant.toString().contains(GROUP.dn()));
    }
}
