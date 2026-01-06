package com.winllc.innoutwork.service;

import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserRecordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRecordRepository userRecordRepository;
    @Mock
    private LdapService ldapService;

    @InjectMocks
    private UserService userService;

    @Test
    void getUserByDn() {
        LdapDn dn = LdapDn.builder().dn("cn=user").build();

        when(userRecordRepository.findByDnIgnoreCase(dn.dn()))
                .thenReturn(Optional.of(
                        UserRecord.builder()
                        .dn(dn.dn())
                        .build()));

        Optional<UserRecord> userByDn = userService.getUserByDn(dn);

        assertTrue(userByDn.isPresent());
    }

    @Test
    void updateProfile() {
        throw new IllegalStateException("Not yet implemented");
    }

    @Test
    void updateRole() {
        throw new IllegalStateException("Not yet implemented");
    }

    @Test
    void updateGroupFavorite() {
        throw new IllegalStateException("Not yet implemented");
    }

    @Test
    void getUserDetails() {
        throw new IllegalStateException("Not yet implemented");
    }
}