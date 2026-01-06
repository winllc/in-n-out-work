package com.winllc.innoutwork.controller;

import com.winllc.innoutwork.config.ApplicationProperties;
import com.winllc.innoutwork.repository.UserRecordRepository;
import com.winllc.innoutwork.service.CacheService;
import com.winllc.innoutwork.service.PermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = HomeController.class, useDefaultFilters = true)
//@SpringBootTest(classes = {}, properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc(addFilters = false)
@EnableConfigurationProperties(ApplicationProperties.class)
@TestPropertySource(properties = {
        "app.cacheDurationRefreshMinutes=5",
        "app.cacheDurationExpirationMinutes=30"
})
class HomeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    CacheService cacheService;

    @MockBean
    UserRecordRepository userRecordRepository;

    @MockBean
    PermissionService permissionService;

    @Test
    void home_returnsOk_andRendersHomeView() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("home"));
    }
}
