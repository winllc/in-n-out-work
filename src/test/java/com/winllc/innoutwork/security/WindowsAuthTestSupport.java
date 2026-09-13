package com.winllc.innoutwork.security;

import com.winllc.innoutwork.data.CheckInOut;
import com.winllc.innoutwork.model.CheckInOutRecord;
import com.winllc.innoutwork.service.CheckInOutService;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import tools.jackson.databind.json.JsonMapper;

import java.security.cert.X509Certificate;
import java.util.Optional;

import javax.security.auth.x500.X500Principal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Shared by the security tests for the check-in calls with Windows sign-in on and off. */
final class WindowsAuthTestSupport {

    static final String CERT_DN = "CN=Alice Adams,OU=Users,DC=winllc,DC=com";
    static final String BOB_DN = "cn=Bob Barker,ou=Users,dc=winllc,dc=com";

    private WindowsAuthTestSupport() {
    }

    static X509Certificate cert(String dn) {
        X509Certificate cert = mock(X509Certificate.class);
        X500Principal principal = new X500Principal(dn);
        when(cert.getSubjectDN()).thenReturn(principal);
        when(cert.getSubjectX500Principal()).thenReturn(principal);
        return cert;
    }

    static UserDetails user(String dn) {
        return User.withUsername(dn).password("").authorities("USER").build();
    }

    static String body(String windowsUserId) {
        CheckInOut checkInOut = new CheckInOut();
        checkInOut.setWindowsUserId(windowsUserId);
        return JsonMapper.builder().build().writeValueAsString(checkInOut);
    }

    static void recordsAreSaved(CheckInOutService checkIns) {
        when(checkIns.lookupBySessionId(any())).thenReturn(Optional.empty());
        when(checkIns.saveCheckInOutRecord(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    static CheckInOutRecord savedRecord(CheckInOutService checkIns) {
        ArgumentCaptor<CheckInOutRecord> saved = ArgumentCaptor.forClass(CheckInOutRecord.class);
        verify(checkIns).saveCheckInOutRecord(saved.capture());
        return saved.getValue();
    }

    static void appUsersResolveByDn(AppUserDetailsService appUsers) {
        when(appUsers.loadUserByUsername(anyString())).thenAnswer(inv -> user(inv.getArgument(0)));
    }
}
