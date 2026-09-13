package com.winllc.innoutwork.security;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.config.SecurityConfig;
import com.winllc.innoutwork.config.WindowsAuthSecurityConfig;
import com.winllc.innoutwork.data.LdapUser;
import com.winllc.innoutwork.rest.CheckInOutRestService;
import com.winllc.innoutwork.service.CheckInOutService;
import com.winllc.innoutwork.service.LdapService;
import org.apache.kerby.kerberos.kerb.client.JaasKrbUtil;
import org.apache.kerby.kerberos.kerb.server.SimpleKdcServer;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.security.auth.Subject;
import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivilegedExceptionAction;
import java.util.Base64;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import static com.winllc.innoutwork.security.WindowsAuthTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Windows sign-in with real Kerberos: an in-process KDC issues the tickets, the app loads its key from an
 * exported keytab, and the JDK validates each ticket, as against Active Directory. What a Windows client sends
 * is reproduced with the JDK's own GSS-API: a SPNEGO token for the service principal.
 */
// Settings from application.yml are bound onto the properties bean below, so anything it sets is overridden here.
@WebMvcTest(properties = {"application.windows-auth.enabled=true", "application.windows-auth.account-attribute=uid"})
@Import({SecurityConfig.class, WindowsAuthSecurityConfig.class, CheckInOutRestService.class,
        WindowsAuthKerberosEndToEndTest.Beans.class})
class WindowsAuthKerberosEndToEndTest {

    private static final String REALM = "EXAMPLE.COM";
    private static final String SERVICE = "HTTP/localhost@" + REALM;
    private static final String OTHER_SERVICE = "HTTP/elsewhere@" + REALM;
    private static final String BOB = "bob@" + REALM;

    private static final Path WORK_DIR;
    private static final File SERVICE_KEYTAB;
    private static final File CLIENT_KEYTAB;
    private static final SimpleKdcServer KDC;

    static {
        try {
            WORK_DIR = Files.createTempDirectory("kdc");
            SERVICE_KEYTAB = WORK_DIR.resolve("http.keytab").toFile();
            CLIENT_KEYTAB = WORK_DIR.resolve("client.keytab").toFile();

            KDC = new SimpleKdcServer();
            KDC.setWorkDir(WORK_DIR.toFile());
            KDC.setKdcRealm(REALM);
            KDC.setKdcHost("localhost");
            KDC.setAllowUdp(false);
            KDC.setAllowTcp(true);
            try (ServerSocket socket = new ServerSocket(0)) {
                KDC.setKdcTcpPort(socket.getLocalPort());
            }
            KDC.init(); // also writes krb5.conf for this realm and points java.security.krb5.conf at it
            KDC.start();

            KDC.createPrincipal(SERVICE);
            KDC.exportPrincipal(SERVICE, SERVICE_KEYTAB);   // what ktpass produces for the app
            KDC.createPrincipal(OTHER_SERVICE);
            KDC.createPrincipal(BOB);
            KDC.exportPrincipal(BOB, CLIENT_KEYTAB);        // stands in for Bob's Windows logon
        } catch (Exception e) {
            throw new IllegalStateException("Could not start the test KDC", e);
        }
    }

    @AfterAll
    static void stopKdc() throws Exception {
        KDC.stop();
        try (Stream<Path> files = Files.walk(WORK_DIR)) {
            files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    AppUserDetailsService appUsers;
    @MockitoBean
    BaseLdapPathContextSource contextSource;
    @MockitoBean
    CheckInOutService checkIns;
    @MockitoBean
    LdapService ldapService;

    @Configuration
    @EnableWebSecurity
    static class Beans {
        @Bean
        ApplicationProperties applicationProperties() {
            ApplicationProperties properties = new ApplicationProperties();
            properties.setUserBaseDn("dc=winllc,dc=com");
            properties.getWindowsAuth().setEnabled(true);
            properties.getWindowsAuth().setServicePrincipal(SERVICE);
            properties.getWindowsAuth().setKeytabLocation(SERVICE_KEYTAB.getAbsolutePath());
            properties.getWindowsAuth().setAccountAttribute("uid");
            return properties;
        }
    }

    @BeforeEach
    void setUp() {
        appUsersResolveByDn(appUsers);
        recordsAreSaved(checkIns);
        when(ldapService.lookupUniqueUser("uid", "bob")).thenReturn(Optional.of(LdapUser.builder().dn(BOB_DN).build()));
    }

    /** A SPNEGO token from Bob for {@code servicePrincipal}, as Windows puts in the Authorization header. */
    @SuppressWarnings("removal") // Subject.doAs is what the JDK's Kerberos code reads credentials from on Java 21
    private static String negotiateHeader(String servicePrincipal) throws Exception {
        Subject bob = JaasKrbUtil.loginUsingKeytab(BOB, CLIENT_KEYTAB);
        byte[] token = Subject.doAs(bob, (PrivilegedExceptionAction<byte[]>) () -> {
            GSSManager manager = GSSManager.getInstance();
            GSSName service = manager.createName(servicePrincipal, new Oid("1.2.840.113554.1.2.2.1"));
            GSSContext context = manager.createContext(service, new Oid("1.3.6.1.5.5.2"), null, GSSContext.DEFAULT_LIFETIME);
            try {
                return context.initSecContext(new byte[0], 0, 0);
            } finally {
                context.dispose();
            }
        });
        return "Negotiate " + Base64.getEncoder().encodeToString(token);
    }

    @Test
    void aRealTicketSignsInAsTheDirectoryUser() throws Exception {
        mockMvc.perform(post("/api/check/in").header("Authorization", negotiateHeader(SERVICE))
                        .contentType(MediaType.APPLICATION_JSON).content(body("someone-else")))
                .andExpect(status().isOk());

        var saved = savedRecord(checkIns);
        assertEquals(BOB_DN, saved.getDn());
        assertEquals(BOB, saved.getWindowsUserId());
    }

    /** A ticket Bob got for a different service is not proof of anything to this one. */
    @Test
    void aTicketForAnotherServiceIsRefused() throws Exception {
        mockMvc.perform(post("/api/check/in").header("Authorization", negotiateHeader(OTHER_SERVICE))
                        .contentType(MediaType.APPLICATION_JSON).content(body("bob")))
                .andExpect(status().isUnauthorized());

        verify(checkIns, never()).saveCheckInOutRecord(any());
    }

    @Test
    void aTamperedTicketIsRefused() throws Exception {
        String header = negotiateHeader(SERVICE);
        byte[] token = Base64.getDecoder().decode(header.substring("Negotiate ".length()));
        token[token.length - 20] ^= 0x5A;

        mockMvc.perform(post("/api/check/in").header("Authorization", "Negotiate " + Base64.getEncoder().encodeToString(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body("bob")))
                .andExpect(status().isUnauthorized());

        verify(checkIns, never()).saveCheckInOutRecord(any());
    }
}
