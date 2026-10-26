package com.sdigital.platform.dashboard.controller;

import com.sdigital.platform.common.exception.GlobalExceptionHandler;
import com.sdigital.platform.dashboard.dto.DashboardSummaryResponse;
import com.sdigital.platform.dashboard.dto.EntityStatusStatistics;
import com.sdigital.platform.dashboard.service.DashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP tests for {@link DashboardController}. {@link DashboardService} is mocked: only the
 * HTTP/JSON contract is exercised here.
 *
 * <p>Security filters are disabled ({@code addFilters = false}) because this slice focuses on
 * the controller contract; authentication and authorization are covered by
 * {@code DashboardAuthorizationSecurityTest}.</p>
 */
@WebMvcTest(controllers = DashboardController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;

    @Test
    void getSummaryReturns200WithExactContractFields() throws Exception {
        when(dashboardService.getSummary()).thenReturn(new DashboardSummaryResponse(
                new EntityStatusStatistics(25, 20, 5),
                new EntityStatusStatistics(8, 6, 2),
                new EntityStatusStatistics(12, 10, 2),
                Instant.parse("2026-10-21T10:30:00Z")));

        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.users.total").value(25))
                .andExpect(jsonPath("$.users.active").value(20))
                .andExpect(jsonPath("$.users.inactive").value(5))
                .andExpect(jsonPath("$.agencies.total").value(8))
                .andExpect(jsonPath("$.agencies.active").value(6))
                .andExpect(jsonPath("$.agencies.inactive").value(2))
                .andExpect(jsonPath("$.functions.total").value(12))
                .andExpect(jsonPath("$.functions.active").value(10))
                .andExpect(jsonPath("$.functions.inactive").value(2))
                .andExpect(jsonPath("$.generatedAt").value("2026-10-21T10:30:00Z"))
                .andExpect(jsonPath("$.generatedAt", matchesPattern(".*Z$")));

        verify(dashboardService).getSummary();
        verifyNoMoreInteractions(dashboardService);
    }

    @Test
    void getSummaryDoesNotExposeEntityInternals() throws Exception {
        when(dashboardService.getSummary()).thenReturn(new DashboardSummaryResponse(
                new EntityStatusStatistics(1, 1, 0),
                new EntityStatusStatistics(1, 1, 0),
                new EntityStatusStatistics(1, 1, 0),
                Instant.parse("2026-10-21T10:30:00Z")));

        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users.id").doesNotExist())
                .andExpect(jsonPath("$.users.email").doesNotExist())
                .andExpect(jsonPath("$.agencies.code").doesNotExist())
                .andExpect(jsonPath("$.functions.code").doesNotExist())
                .andExpect(jsonPath("$.keycloakId").doesNotExist());
    }

    @Test
    void unexpectedExceptionReturns500WithGenericMessage() throws Exception {
        when(dashboardService.getSummary()).thenThrow(new RuntimeException("sensitive internal detail"));

        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sensitive"))));
    }
}
