package com.sdigital.platform.audit.controller;

import com.sdigital.platform.audit.dto.AuditLogFilter;
import com.sdigital.platform.audit.dto.AuditLogResponse;
import com.sdigital.platform.audit.entity.AuditOutcome;
import com.sdigital.platform.audit.service.AuditLogQueryService;
import com.sdigital.platform.common.exception.GlobalExceptionHandler;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP tests for {@link AuditLogController} and {@link GlobalExceptionHandler}.
 * {@link AuditLogQueryService} is mocked: only the HTTP layer is exercised here.
 *
 * <p>Security filters are disabled ({@code addFilters = false}) because this slice focuses
 * on the controller/handler contract; authentication and authorization are covered by
 * {@code AuditLogAuthorizationSecurityTest}.</p>
 */
@WebMvcTest(controllers = AuditLogController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AuditLogControllerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-01-01T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditLogQueryService auditLogQueryService;

    private AuditLogResponse sample(UUID id) {
        return new AuditLogResponse(id, OCCURRED_AT, UUID.randomUUID(),
                "USER_UPDATED", "USER", UUID.randomUUID().toString(),
                AuditOutcome.SUCCESS, Map.of("fields", List.of("email")));
    }

    @Test
    void getAllReturnsPagedContent() throws Exception {
        UUID id = UUID.randomUUID();
        when(auditLogQueryService.search(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sample(id)), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(id.toString()))
                .andExpect(jsonPath("$.content[0].occurredAt").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.content[0].action").value("USER_UPDATED"))
                .andExpect(jsonPath("$.content[0].entityType").value("USER"))
                .andExpect(jsonPath("$.content[0].outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.content[0].details.fields[0]").value("email"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getAllAppliesDefaultPaginationAndSort() throws Exception {
        when(auditLogQueryService.search(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/audit-logs"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(auditLogQueryService).search(any(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "occurredAt"));
    }

    @Test
    void getAllCapsPageSizeAtOneHundred() throws Exception {
        when(auditLogQueryService.search(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        mockMvc.perform(get("/api/v1/audit-logs").param("size", "1000"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(auditLogQueryService).search(any(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void getAllAcceptsEveryAllowedSortProperty() throws Exception {
        when(auditLogQueryService.search(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        for (String property : List.of("occurredAt", "action", "entityType", "outcome", "actorUserId")) {
            mockMvc.perform(get("/api/v1/audit-logs").param("sort", property + ",asc"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void getAllRejectsForbiddenSortProperty() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs").param("sort", "details,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail", Matchers.containsString("details")));

        verify(auditLogQueryService, never()).search(any(), any(Pageable.class));
    }

    @Test
    void getAllPassesEveryFilterToTheService() throws Exception {
        when(auditLogQueryService.search(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        UUID entityId = UUID.randomUUID();
        UUID actorUserId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/audit-logs")
                        .param("action", "USER_UPDATED")
                        .param("entityType", "USER")
                        .param("entityId", entityId.toString())
                        .param("actorUserId", actorUserId.toString())
                        .param("outcome", "SUCCESS")
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-12-31T23:59:59Z")
                        .param("page", "2")
                        .param("size", "5")
                        .param("sort", "action,asc"))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditLogFilter> filter = ArgumentCaptor.forClass(AuditLogFilter.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(auditLogQueryService).search(filter.capture(), pageable.capture());
        assertThat(filter.getValue().action()).isEqualTo("USER_UPDATED");
        assertThat(filter.getValue().entityType()).isEqualTo("USER");
        assertThat(filter.getValue().entityId()).isEqualTo(entityId);
        assertThat(filter.getValue().actorUserId()).isEqualTo(actorUserId);
        assertThat(filter.getValue().outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(filter.getValue().from()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(filter.getValue().to()).isEqualTo(Instant.parse("2026-12-31T23:59:59Z"));
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "action"));
    }

    @Test
    void getAllWithoutFiltersPassesNullCriteriaToTheService() throws Exception {
        when(auditLogQueryService.search(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/audit-logs"))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditLogFilter> filter = ArgumentCaptor.forClass(AuditLogFilter.class);
        verify(auditLogQueryService).search(filter.capture(), any(Pageable.class));
        assertThat(filter.getValue().action()).isNull();
        assertThat(filter.getValue().entityType()).isNull();
        assertThat(filter.getValue().entityId()).isNull();
        assertThat(filter.getValue().actorUserId()).isNull();
        assertThat(filter.getValue().outcome()).isNull();
        assertThat(filter.getValue().from()).isNull();
        assertThat(filter.getValue().to()).isNull();
    }

    @Test
    void getAllReturns400WhenEntityIdIsNotAUuid() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs").param("entityId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail", Matchers.containsString("entityId")));

        verify(auditLogQueryService, never()).search(any(), any(Pageable.class));
    }

    @Test
    void getAllReturns400WhenActorUserIdIsNotAUuid() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs").param("actorUserId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail", Matchers.containsString("actorUserId")));

        verify(auditLogQueryService, never()).search(any(), any(Pageable.class));
    }

    @Test
    void getAllReturns400WhenOutcomeIsInvalid() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs").param("outcome", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail", Matchers.containsString("outcome")));

        verify(auditLogQueryService, never()).search(any(), any(Pageable.class));
    }

    @Test
    void getAllReturns400WhenDateBoundIsNotAnIsoInstant() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs").param("from", "not-a-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail", Matchers.containsString("from")));

        verify(auditLogQueryService, never()).search(any(), any(Pageable.class));
    }

    @Test
    void getAllReturns400WhenFromIsAfterTo() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs")
                        .param("from", "2026-12-31T00:00:00Z")
                        .param("to", "2026-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Invalid date range: 'from' must not be after 'to'"));

        verify(auditLogQueryService, never()).search(any(), any(Pageable.class));
    }

    @Test
    void getAllReturns400WhenActionIsLongerThanTheColumn() throws Exception {
        mockMvc.perform(get("/api/v1/audit-logs").param("action", "A".repeat(65)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verify(auditLogQueryService, never()).search(any(), any(Pageable.class));
    }

    @Test
    void getAllTreatsBlankFiltersAsAbsent() throws Exception {
        when(auditLogQueryService.search(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/audit-logs")
                        .param("action", "  ")
                        .param("entityType", " "))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditLogFilter> filter = ArgumentCaptor.forClass(AuditLogFilter.class);
        verify(auditLogQueryService).search(filter.capture(), any(Pageable.class));
        assertThat(filter.getValue().action()).isNull();
        assertThat(filter.getValue().entityType()).isNull();
    }

    /**
     * The audit trail is read-only: the collection route only answers GET, and no
     * per-entry route exists at all.
     */
    @Test
    void noWriteRouteIsExposed() throws Exception {
        mockMvc.perform(post("/api/v1/audit-logs")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put("/api/v1/audit-logs/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/audit-logs/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/audit-logs/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
