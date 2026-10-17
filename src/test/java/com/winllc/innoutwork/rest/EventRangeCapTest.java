package com.winllc.innoutwork.rest;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.constant.UserRoleEnum;
import com.winllc.innoutwork.repository.CheckInOutRecordRepository;
import com.winllc.innoutwork.repository.GlobalCalendarRecordRepository;
import com.winllc.innoutwork.repository.UserEventRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.winllc.innoutwork.support.TestCerts.cert;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every API that creates events holds its date range to application.events.max-range-days, refusing a longer one
 * with 400 before anything is written: a person's own calendar and the organization calendar.
 */
@WebMvcTest
@Import({UserEventRestService.class, SettingsRestService.class, EventRangeCapTest.TestSecurityConfig.class})
class EventRangeCapTest {

    private static final String ADMIN = "CN=admin,OU=Users,DC=winllc,DC=com";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean UserEventRecordRepository userEventRecordRepository;
    @MockitoBean CheckInOutRecordRepository checkInOutRecordRepository;
    @MockitoBean GlobalCalendarRecordRepository globalCalendarRecordRepository;
    @MockitoBean com.winllc.innoutwork.service.StatusPolicy statusPolicy;
    @MockitoBean com.winllc.innoutwork.service.StatusCalendar statusCalendar;
    @MockitoBean com.winllc.innoutwork.service.StatusRequestService statusRequestService;
    @MockitoBean com.winllc.innoutwork.service.Approvers approvers;

    @Configuration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .x509(x509 -> x509.subjectPrincipalRegex("(.*)").userDetailsService(userDetailsService()));
            return http.build();
        }

        @Bean
        UserDetailsService userDetailsService() {
            return username -> User.withUsername(username).password("")
                    .authorities(UserRoleEnum.USER.name(), UserRoleEnum.ADMIN.name()).build();
        }

        @Bean
        ApplicationProperties applicationProperties() {
            ApplicationProperties properties = new ApplicationProperties();
            properties.getEvents().setMaxRangeDays(31);
            return properties;
        }
    }

    @org.junit.jupiter.api.BeforeEach
    void saveReturnsTheRecord() {
        org.mockito.Mockito.when(globalCalendarRecordRepository.save(any())).thenAnswer(inv -> {
            com.winllc.innoutwork.model.GlobalCalendarRecord record = inv.getArgument(0);
            record.setId(1L);
            return record;
        });
    }

    @Test
    void aPersonsStatusOverTooLongARangeIsRefused() throws Exception {
        mockMvc.perform(post("/api/event/update").with(x509(cert(ADMIN))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromDate\":\"2026-11-01\",\"toDate\":\"2026-12-03\",\"status\":\"LATE_ARRIVAL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("range_too_long"));

        verify(userEventRecordRepository, never()).save(any());
    }

    @Test
    void aBackwardsRangeAndAnUnknownStatusAreRefused() throws Exception {
        mockMvc.perform(post("/api/event/update").with(x509(cert(ADMIN))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromDate\":\"2026-11-05\",\"toDate\":\"2026-11-01\",\"status\":\"LATE_ARRIVAL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_range"));
        mockMvc.perform(post("/api/event/update").with(x509(cert(ADMIN))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromDate\":\"2026-11-01\",\"toDate\":\"2026-11-02\",\"status\":\"ON_A_BEACH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_status"));

        verify(userEventRecordRepository, never()).save(any());
    }

    @Test
    void anOrganizationEventOverTooLongARangeIsRefused() throws Exception {
        mockMvc.perform(post("/api/settings/calendar/update").with(x509(cert(ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromDate\":\"2026-01-01T05:00:00.000Z\",\"toDate\":\"2027-01-01T05:00:00.000Z\","
                                + "\"title\":\"Shutdown\",\"holiday\":\"true\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("range_too_long"));

        verify(globalCalendarRecordRepository, never()).save(any());
    }

    @Test
    void aRangeWithinTheCapIsSaved() throws Exception {
        mockMvc.perform(post("/api/settings/calendar/update").with(x509(cert(ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromDate\":\"2026-12-24\",\"toDate\":\"2026-12-27\",\"title\":\"Holidays\","
                                + "\"holiday\":\"true\"}"))
                .andExpect(status().isOk());

        verify(globalCalendarRecordRepository, org.mockito.Mockito.times(3)).save(any());
    }
}
