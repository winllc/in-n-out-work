package com.winllc.innoutwork.service;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.data.GroupFavorite;
import com.winllc.innoutwork.data.LdapDn;
import com.winllc.innoutwork.data.LdapGroup;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.data.ProfileForm;
import com.winllc.innoutwork.data.UserStatus;
import com.winllc.innoutwork.model.UserRecord;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.repository.UserRecordRepository;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceTest {

    private static final String USER_DN = "cn=alice,ou=Users,dc=winllc,dc=com";

    @Mock
    private UserRecordRepository userRecordRepository;
    @Mock
    private LdapService ldapService;
    @Mock
    private ApplicationProperties properties;
    @Mock
    private CheckInOutService checkInOutService;
    @Mock
    private UserEventRecordRepository userEventRecordRepository;
    @Mock
    private HttpSession session;
    @Mock
    private Authentication authentication;

    @InjectMocks
    private UserService userService;

    @BeforeEach
    void setUp() {
        // UserService takes its main collaborators via constructor but these two via field
        // @Autowired, which @InjectMocks skips once it has picked the constructor.
        ReflectionTestUtils.setField(userService, "checkInOutService", checkInOutService);
        ReflectionTestUtils.setField(userService, "userEventRecordRepository", userEventRecordRepository);

        // The service saves and returns the record it was given.
        when(userRecordRepository.save(any(UserRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

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

    /**
     * When there is no local record yet, the LDAP entry is used to seed one.
     */
    @Test
    void getUserByDnFallsBackToLdapAndPersistsTheResult() {
        LdapDn dn = LdapDn.builder().dn(USER_DN).build();

        when(userRecordRepository.findByDnIgnoreCase(dn.dn())).thenReturn(Optional.empty());
        when(ldapService.lookupUser(dn)).thenReturn(Optional.of(
                LdapUser.builder().dn(USER_DN).employeeType("CIV").build()));

        Optional<UserRecord> result = userService.getUserByDn(dn);

        assertTrue(result.isPresent());
        assertEquals(USER_DN, result.get().getDn());
        verify(userRecordRepository).save(any(UserRecord.class));
    }

    @Test
    void getUserByDnReturnsEmptyWhenUnknownToLdap() {
        LdapDn dn = LdapDn.builder().dn(USER_DN).build();

        when(userRecordRepository.findByDnIgnoreCase(dn.dn())).thenReturn(Optional.empty());
        when(ldapService.lookupUser(dn)).thenReturn(Optional.empty());

        assertTrue(userService.getUserByDn(dn).isEmpty());
        verify(userRecordRepository, never()).save(any());
    }

    @Test
    void updateProfileUpdatesTheExistingRecord() {
        UserRecord existing = UserRecord.builder().dn(USER_DN).notes("old notes").build();

        when(authentication.getName()).thenReturn(USER_DN);
        when(userRecordRepository.findByDnIgnoreCase(USER_DN)).thenReturn(Optional.of(existing));

        ProfileForm form = ProfileForm.builder()
                .notes("new notes")
                .loginTime("08:30:00")
                .build();

        UserRecord saved = userService.updateProfile(authentication, form);

        assertEquals("new notes", saved.getNotes());
        assertEquals(LocalTime.of(8, 30), saved.getChosenLoginTime());
        assertEquals(USER_DN, saved.getDn());
    }

    /**
     * A user editing their profile before any record exists gets one created against their DN.
     */
    @Test
    void updateProfileCreatesARecordWhenNoneExists() {
        when(authentication.getName()).thenReturn(USER_DN);
        when(userRecordRepository.findByDnIgnoreCase(USER_DN)).thenReturn(Optional.empty());

        UserRecord saved = userService.updateProfile(authentication,
                ProfileForm.builder().notes("first note").build());

        assertEquals(USER_DN, saved.getDn());
        assertEquals("first note", saved.getNotes());
    }

    @Test
    void updateProfileLeavesLoginTimeUnsetWhenBlank() {
        UserRecord existing = UserRecord.builder().dn(USER_DN).build();

        when(authentication.getName()).thenReturn(USER_DN);
        when(userRecordRepository.findByDnIgnoreCase(USER_DN)).thenReturn(Optional.of(existing));

        UserRecord saved = userService.updateProfile(authentication,
                ProfileForm.builder().notes("n").loginTime("  ").build());

        assertNull(saved.getChosenLoginTime());
    }

    @Test
    void updateRoleSetsTheRoleOnTheExistingRecord() {
        LdapDn dn = LdapDn.builder().dn(USER_DN).build();
        UserRecord existing = UserRecord.builder().dn(USER_DN).userRole(UserRoleEnum.USER).build();

        when(userRecordRepository.findByDnIgnoreCase(USER_DN)).thenReturn(Optional.of(existing));

        UserRecord saved = userService.updateRole(dn, UserRoleEnum.ADMIN);

        assertEquals(UserRoleEnum.ADMIN, saved.getUserRole());
        assertEquals(USER_DN, saved.getDn());
    }

    @Test
    void updateRoleCreatesARecordWhenNoneExists() {
        LdapDn dn = LdapDn.builder().dn(USER_DN).build();

        when(userRecordRepository.findByDnIgnoreCase(USER_DN)).thenReturn(Optional.empty());

        UserRecord saved = userService.updateRole(dn, UserRoleEnum.MANAGER);

        assertEquals(USER_DN, saved.getDn());
        assertEquals(UserRoleEnum.MANAGER, saved.getUserRole());
    }

    @Test
    void updateGroupFavoriteAddsTheGroupWhenSelected() {
        UserRecord existing = UserRecord.builder().dn(USER_DN).build();

        when(authentication.getName()).thenReturn(USER_DN);
        when(userRecordRepository.findByDnIgnoreCase(USER_DN)).thenReturn(Optional.of(existing));

        GroupFavorite favorite = new GroupFavorite();
        favorite.setGroupDn("ou=Groups,dc=winllc,dc=com");
        favorite.setSelected(true);

        UserRecord saved = userService.updateGroupFavorite(authentication, favorite);

        assertTrue(saved.containsGroupDn("ou=Groups,dc=winllc,dc=com"));
    }

    @Test
    void updateGroupFavoriteRemovesTheGroupWhenDeselected() {
        UserRecord existing = UserRecord.builder().dn(USER_DN).build();
        existing.addGroup("ou=Groups,dc=winllc,dc=com");
        existing.addGroup("ou=Companies,dc=winllc,dc=com");

        when(authentication.getName()).thenReturn(USER_DN);
        when(userRecordRepository.findByDnIgnoreCase(USER_DN)).thenReturn(Optional.of(existing));

        GroupFavorite favorite = new GroupFavorite();
        favorite.setGroupDn("ou=Groups,dc=winllc,dc=com");
        favorite.setSelected(false);

        UserRecord saved = userService.updateGroupFavorite(authentication, favorite);

        assertFalse(saved.containsGroupDn("ou=Groups,dc=winllc,dc=com"));
        assertTrue(saved.containsGroupDn("ou=Companies,dc=winllc,dc=com"),
                "removing one favorite must not drop the others");
    }

    /**
     * getUserDetails stitches together the local record, the user's LDAP groups, today's
     * check-in status and the manager lookup into a single UserStatus.
     */
    @Test
    void getUserDetailsCombinesRecordGroupsStatusAndManager() {
        LdapDn dn = LdapDn.builder().dn(USER_DN).build();

        UserRecord record = UserRecord.builder()
                .dn(USER_DN)
                .notes("some notes")
                .userRole(UserRoleEnum.ADMIN)
                .organization("WINLLC")
                .employeeType("CIV")
                .averageLoginTime(LocalTime.of(8, 15))
                .build();

        when(userRecordRepository.findByDnIgnoreCase(USER_DN)).thenReturn(Optional.of(record));

        LdapGroup group = new LdapGroup("ou=Groups,dc=winllc,dc=com", "Groups");
        when(ldapService.findGroupsForUser(USER_DN)).thenReturn(List.of(group));

        // No check-in records today -> status NONE.
        when(checkInOutService.findRecordsForUser(eq(USER_DN), any())).thenReturn(List.of());
        when(userEventRecordRepository.findByDnIgnoreCaseAndDate(anyString(), any()))
                .thenReturn(List.of());

        // Manager resolution: the user's managerId is looked up as a second LDAP user.
        when(properties.getManagerLdapIdAttribute()).thenReturn("Email");
        when(ldapService.lookupUser(dn)).thenReturn(Optional.of(
                LdapUser.builder().dn(USER_DN).managerId("boss@winllc.com").build()));
        when(ldapService.lookupUser("Email", "boss@winllc.com")).thenReturn(Optional.of(
                LdapUser.builder().dn("cn=boss,ou=Users,dc=winllc,dc=com").build()));

        UserStatus details = userService.getUserDetails(dn, session);

        assertEquals(USER_DN, details.getDn());
        assertEquals("some notes", details.getNotes());
        assertEquals(UserRoleEnum.ADMIN.name(), details.getRole());
        assertEquals(List.of(group), details.getMemberOf());
        assertEquals("NONE", details.getStatus());
        assertEquals("WINLLC", details.getOrganization());
        assertEquals("CIV", details.getEmployeeType());
        assertEquals("cn=boss,ou=Users,dc=winllc,dc=com", details.getManagerDn());
        assertNotNull(details.getAverageLoginTime());
    }

    @Test
    void getUserDetailsDefaultsRoleToUserWhenUnset() {
        LdapDn dn = LdapDn.builder().dn(USER_DN).build();

        when(userRecordRepository.findByDnIgnoreCase(USER_DN)).thenReturn(Optional.of(
                UserRecord.builder().dn(USER_DN).build()));
        when(ldapService.findGroupsForUser(USER_DN)).thenReturn(List.of());
        when(checkInOutService.findRecordsForUser(eq(USER_DN), any())).thenReturn(List.of());
        when(userEventRecordRepository.findByDnIgnoreCaseAndDate(anyString(), any()))
                .thenReturn(List.of());
        when(ldapService.lookupUser(dn)).thenReturn(Optional.empty());

        UserStatus details = userService.getUserDetails(dn, session);

        assertEquals(UserRoleEnum.USER.name(), details.getRole());
        assertNull(details.getManagerDn());
    }

    @Test
    void getUserDetailsLeavesManagerUnsetWhenTheUserHasNoManagerId() {
        LdapDn dn = LdapDn.builder().dn(USER_DN).build();

        when(userRecordRepository.findByDnIgnoreCase(USER_DN)).thenReturn(Optional.of(
                UserRecord.builder().dn(USER_DN).build()));
        when(ldapService.findGroupsForUser(USER_DN)).thenReturn(List.of());
        when(checkInOutService.findRecordsForUser(eq(USER_DN), any())).thenReturn(List.of());
        when(userEventRecordRepository.findByDnIgnoreCaseAndDate(anyString(), any()))
                .thenReturn(List.of());
        when(ldapService.lookupUser(dn)).thenReturn(Optional.of(
                LdapUser.builder().dn(USER_DN).managerId(null).build()));

        UserStatus details = userService.getUserDetails(dn, session);

        assertNull(details.getManagerDn());
        verify(ldapService, never()).lookupUser(anyString(), anyString());
    }
}
