package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.RequestState;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.constant.UserStatusEnum;
import com.winllc.innoutwork.controller.ApprovalsController;
import com.winllc.innoutwork.model.StatusRequest;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.GlobalCalendarRecordRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import com.winllc.innoutwork.security.AccessPolicy;
import com.winllc.innoutwork.service.Approvers;
import com.winllc.innoutwork.service.InvalidRequestException;
import com.winllc.innoutwork.service.StatusCalendar;
import com.winllc.innoutwork.service.StatusPolicy;
import com.winllc.innoutwork.service.StatusRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

import static com.winllc.innoutwork.support.TestCerts.cert;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/**
 * The status classes, enforced by the server whatever the page offered: a person sets late arrival themselves,
 * requests leave (which reaches nothing until approved), and cannot record a manager's status; for someone else only
 * their manager, a delegate or an administrator sets anything. And the approvals API and page reach the service.
 */
@WebMvcTest
@Import({UserEventRestService.class, StatusRequestRestService.class, ApprovalsController.class, StatusPolicy.class,
        StatusClassesEndpointTest.TestSecurityConfig.class})
class StatusClassesEndpointTest {

    private static final String ALICE = "CN=alice,OU=Users,DC=winllc,DC=com";
    private static final String MANAGER = "CN=manny,OU=Users,DC=winllc,DC=com";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean UserEventRecordRepository userEventRecordRepository;
    @MockitoBean CheckInOutRecordRepository checkInOutRecordRepository;
    @MockitoBean GlobalCalendarRecordRepository globalCalendarRecordRepository;
    @MockitoBean StatusCalendar statusCalendar;
    @MockitoBean StatusRequestService requestService;
    @MockitoBean Approvers approvers;
    @MockitoBean(name = "accessPolicy") AccessPolicy accessPolicy;
    // The combined group and team calendars read the directory; nothing here goes near them.
    @MockitoBean com.winllc.innoutwork.service.LdapService ldapService;
    @MockitoBean com.winllc.innoutwork.service.UserService userService;

    @Configuration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            http
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .x509(x509 -> x509.subjectPrincipalRegex("(.*)").userDetailsService(userDetailsService()));
            return http.build();
        }

        @Bean
        UserDetailsService userDetailsService() {
            return username -> User.withUsername(username).password("").authorities(UserRoleEnum.USER.name()).build();
        }

        @Bean
        ApplicationProperties applicationProperties() {
            return new ApplicationProperties();
        }
    }

    private static String body(String status) {
        return "{\"fromDate\":\"2026-11-02\",\"toDate\":\"2026-11-04\",\"status\":\"" + status + "\"}";
    }

    private static StatusRequest pending(long id) {
        return StatusRequest.builder().id(id).requesterDn(ALICE).status(UserStatusEnum.SCHEDULED_LEAVE)
                .fromDate(LocalDate.of(2026, 11, 2)).toDate(LocalDate.of(2026, 11, 3)).state(RequestState.REQUESTED)
                .createdAt(ZonedDateTime.now()).build();
    }

    // --- one's own calendar -----------------------------------------------------------------------------------

    @Test
    void aStatusThePersonSetsIsSetAtOnce() throws Exception {
        mockMvc.perform(post("/api/event/update").with(csrf()).with(x509(cert(ALICE))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromDate\":\"2026-11-02\",\"toDate\":\"2026-11-03\",\"status\":\"LATE_ARRIVAL\","
                                + "\"lateArrivalTime\":\"10:30\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("set"));

        verify(statusCalendar).set(eq(ALICE), eq(UserStatusEnum.LATE_ARRIVAL),
                eq(List.of(LocalDate.of(2026, 11, 2))), eq(java.time.LocalTime.of(10, 30)), isNull());
        verify(requestService, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void aStatusThatExcusesAnAbsenceBecomesARequest() throws Exception {
        when(requestService.create(eq(ALICE), eq(UserStatusEnum.SCHEDULED_LEAVE), any(), any(), any()))
                .thenReturn(pending(7));

        mockMvc.perform(post("/api/event/update").with(csrf()).with(x509(cert(ALICE))).contentType(MediaType.APPLICATION_JSON)
                        .content(body("SCHEDULED_LEAVE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("requested"))
                .andExpect(jsonPath("$.requestId").value(7));

        verify(statusCalendar, never()).set(any(), any(), anyList(), any(), any());
    }

    @Test
    void absentExcusedCanNoLongerBeSetByThePersonThemselves() throws Exception {
        when(requestService.create(any(), any(), any(), any(), any())).thenReturn(pending(8));

        mockMvc.perform(post("/api/event/update").with(csrf()).with(x509(cert(ALICE))).contentType(MediaType.APPLICATION_JSON)
                        .content(body("ABSENT_EXCUSED")))
                .andExpect(jsonPath("$.outcome").value("requested"));

        verify(statusCalendar, never()).set(any(), any(), anyList(), any(), any());
    }

    @Test
    void aManagersStatusIsRefusedOnOnesOwnCalendar() throws Exception {
        mockMvc.perform(post("/api/event/update").with(csrf()).with(x509(cert(ALICE))).contentType(MediaType.APPLICATION_JSON)
                        .content(body("ABSENT_UNEXCUSED")))
                .andExpect(status().isForbidden());

        verify(statusCalendar, never()).set(any(), any(), anyList(), any(), any());
        verify(requestService, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void theStatusesOfferedAreTheOnesThePersonMayUse() throws Exception {
        mockMvc.perform(get("/api/event/statuses").with(x509(cert(ALICE))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.status=='LATE_ARRIVAL')].needsApproval").value(false))
                .andExpect(jsonPath("$[?(@.status=='SCHEDULED_LEAVE')].needsApproval").value(true))
                .andExpect(jsonPath("$[?(@.status=='ABSENT')]").isEmpty());
    }

    // --- someone else's -----------------------------------------------------------------------------------------

    @Test
    void theirManagerSetsAnyStatusForThem() throws Exception {
        when(approvers.mayDecide(any(), eq(ALICE))).thenReturn(true);

        mockMvc.perform(post("/api/event/update").with(csrf()).with(x509(cert(MANAGER))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dn\":\"" + ALICE + "\",\"fromDate\":\"2026-11-02\",\"toDate\":\"2026-11-03\","
                                + "\"status\":\"ABSENT_UNEXCUSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("set"));

        verify(statusCalendar).set(eq(ALICE), eq(UserStatusEnum.ABSENT_UNEXCUSED), anyList(), any(), isNull());
    }

    @Test
    void aCoworkerSetsNothingForSomeoneElse() throws Exception {
        when(approvers.mayDecide(any(), anyString())).thenReturn(false);

        mockMvc.perform(post("/api/event/update").with(csrf()).with(x509(cert(MANAGER))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dn\":\"" + ALICE + "\",\"fromDate\":\"2026-11-02\",\"toDate\":\"2026-11-03\","
                                + "\"status\":\"WORK_FROM_HOME\"}"))
                .andExpect(status().isForbidden());

        verify(statusCalendar, never()).set(any(), any(), anyList(), any(), any());
    }

    @Test
    void aTargetThatIsNotADnIsRefused() throws Exception {
        mockMvc.perform(post("/api/event/update").with(csrf()).with(x509(cert(MANAGER))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dn\":\"not a dn\",\"fromDate\":\"2026-11-02\",\"toDate\":\"2026-11-03\","
                                + "\"status\":\"ABSENT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_dn"));
    }

    // --- the approvals API and page -------------------------------------------------------------------------

    @Test
    void theApiCreatesAndDecidesThroughTheService() throws Exception {
        when(requestService.create(eq(ALICE), eq(UserStatusEnum.TDY), any(), any(), eq("conference")))
                .thenReturn(pending(9));
        mockMvc.perform(post("/api/requests").with(csrf()).with(x509(cert(ALICE))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromDate\":\"2026-11-02\",\"toDate\":\"2026-11-04\",\"status\":\"TDY\","
                                + "\"note\":\"conference\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.state").value("REQUESTED"));

        StatusRequest approved = pending(9);
        approved.setState(RequestState.APPROVED);
        when(requestService.approve(any(), eq(9L), eq("ok"))).thenReturn(approved);
        mockMvc.perform(post("/api/requests/9/approve").with(csrf()).with(x509(cert(MANAGER))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("APPROVED"));
    }

    @Test
    void theApiAnswersRefusalsAsTheServiceDecides() throws Exception {
        when(requestService.approve(any(), eq(5L), any())).thenThrow(new AccessDeniedException("not theirs"));
        mockMvc.perform(post("/api/requests/5/approve").with(csrf()).with(x509(cert(ALICE))))
                .andExpect(status().isForbidden());

        when(requestService.reject(any(), eq(6L), any())).thenThrow(InvalidRequestException.conflict("not_pending", "done"));
        mockMvc.perform(post("/api/requests/6/reject").with(csrf()).with(x509(cert(MANAGER))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("not_pending"));
    }

    @Test
    void anOverlongRequestIsRefusedBeforeTheService() throws Exception {
        mockMvc.perform(post("/api/requests").with(csrf()).with(x509(cert(ALICE))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromDate\":\"2026-11-02\",\"toDate\":\"2027-11-04\",\"status\":\"TDY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("range_too_long"));

        verify(requestService, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void theApprovalsPageListsWhatWaitsAndOnesOwn() throws Exception {
        StatusRequest waiting = pending(11);
        waiting.setNote("<script>alert(1)</script>");
        when(requestService.pendingFor(any())).thenReturn(List.of(waiting));
        when(requestService.mine(anyString())).thenReturn(List.of());

        mockMvc.perform(get("/app/approvals").with(x509(cert(MANAGER))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Waiting for your approval")))
                .andExpect(content().string(containsString("/app/approvals/11/approve")))
                .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<script>alert(1)"))));
    }

    @Test
    void thePagesFormsRedirectWithTheOutcome() throws Exception {
        StatusRequest approved = pending(12);
        approved.setState(RequestState.APPROVED);
        when(requestService.approve(any(), eq(12L), any())).thenReturn(approved);
        mockMvc.perform(post("/app/approvals/12/approve").with(csrf()).with(x509(cert(MANAGER))))
                .andExpect(redirectedUrl("/app/approvals"))
                .andExpect(flash().attribute("message", containsString("Approved")));

        when(requestService.cancel(any(), anyLong())).thenThrow(InvalidRequestException.conflict("not_cancellable", "over"));
        mockMvc.perform(post("/app/approvals/13/cancel").with(csrf()).with(x509(cert(ALICE))))
                .andExpect(redirectedUrl("/app/approvals"))
                .andExpect(flash().attribute("error", "over"));
    }
}
