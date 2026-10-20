package com.sdigital.platform.agency.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdigital.platform.agency.dto.AgencyResponse;
import com.sdigital.platform.agency.dto.CreateAgencyRequest;
import com.sdigital.platform.agency.dto.UpdateAgencyRequest;
import com.sdigital.platform.agency.entity.Agency;
import com.sdigital.platform.agency.entity.AgencyStatus;
import com.sdigital.platform.agency.exception.AgencyCodeAlreadyUsedException;
import com.sdigital.platform.agency.exception.AgencyNotFoundException;
import com.sdigital.platform.agency.service.AgencyService;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP tests for {@link AgencyController} and {@link GlobalExceptionHandler}.
 * {@link AgencyService} is mocked: only the HTTP layer is exercised here.
 *
 * <p>Security filters are disabled ({@code addFilters = false}) because this slice focuses
 * on the controller/handler contract; authentication and authorization are covered by
 * {@code ReferenceDataAuthorizationSecurityTest}.</p>
 */
@WebMvcTest(controllers = AgencyController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AgencyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AgencyService agencyService;

    private AgencyResponse sample(UUID id) {
        Instant created = Instant.parse("2026-01-01T10:00:00Z");
        return new AgencyResponse(id, "AG-PARIS-01", "Agency Paris 01", AgencyStatus.ACTIVE, created, created);
    }

    @Test
    void createReturns201WithBodyAndLocation() throws Exception {
        UUID id = UUID.randomUUID();
        when(agencyService.create(any(CreateAgencyRequest.class))).thenReturn(sample(id));
        String body = objectMapper.writeValueAsString(new CreateAgencyRequest("AG-PARIS-01", "Agency Paris 01"));

        mockMvc.perform(post("/api/v1/agencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", Matchers.endsWith("/api/v1/agencies/" + id)))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.code").value("AG-PARIS-01"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void createReturns400WhenBodyInvalid() throws Exception {
        String body = objectMapper.writeValueAsString(new CreateAgencyRequest("", ""));

        mockMvc.perform(post("/api/v1/agencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[*].field", Matchers.hasItems("code", "name")));
    }

    @Test
    void createReturns400WhenBodyMalformed() throws Exception {
        mockMvc.perform(post("/api/v1/agencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void createReturns409WhenCodeAlreadyUsed() throws Exception {
        when(agencyService.create(any(CreateAgencyRequest.class)))
                .thenThrow(new AgencyCodeAlreadyUsedException("AG-PARIS-01"));
        String body = objectMapper.writeValueAsString(new CreateAgencyRequest("AG-PARIS-01", "Agency Paris 01"));

        mockMvc.perform(post("/api/v1/agencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Agency code already in use"));
    }

    @Test
    void getByIdReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(agencyService.getById(id)).thenReturn(sample(id));

        mockMvc.perform(get("/api/v1/agencies/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.code").value("AG-PARIS-01"));
    }

    @Test
    void getByIdReturns404WhenUnknown() throws Exception {
        UUID id = UUID.randomUUID();
        when(agencyService.getById(id)).thenThrow(new AgencyNotFoundException(id));

        mockMvc.perform(get("/api/v1/agencies/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Agency not found"));
    }

    @Test
    void getByIdReturns400WhenUuidMalformed() throws Exception {
        mockMvc.perform(get("/api/v1/agencies/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void getAllReturnsPagedContentAndCapsPageSize() throws Exception {
        when(agencyService.getAll(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sample(UUID.randomUUID())), PageRequest.of(0, 100), 1));

        mockMvc.perform(get("/api/v1/agencies")
                        .param("page", "0")
                        .param("size", "1000")
                        .param("sort", "code,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].code").value("AG-PARIS-01"))
                .andExpect(jsonPath("$.totalElements").value(1));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(agencyService).getAll(any(), any(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "code"));
    }

    @Test
    void getAllWithoutFiltersPassesNullsToService() throws Exception {
        when(agencyService.getAll(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/agencies"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> search = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AgencyStatus> status = ArgumentCaptor.forClass(AgencyStatus.class);
        verify(agencyService).getAll(search.capture(), status.capture(), any(Pageable.class));
        assertThat(search.getValue()).isNull();
        assertThat(status.getValue()).isNull();
    }

    @Test
    void getAllPassesSearchStatusAndPageableToService() throws Exception {
        when(agencyService.getAll(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 5), 0));

        mockMvc.perform(get("/api/v1/agencies")
                        .param("search", "paris")
                        .param("status", "ACTIVE")
                        .param("page", "1")
                        .param("size", "5")
                        .param("sort", "code,asc"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> search = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AgencyStatus> status = ArgumentCaptor.forClass(AgencyStatus.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(agencyService).getAll(search.capture(), status.capture(), pageable.capture());
        assertThat(search.getValue()).isEqualTo("paris");
        assertThat(status.getValue()).isEqualTo(AgencyStatus.ACTIVE);
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    void getAllReturns400WhenStatusIsInvalid() throws Exception {
        mockMvc.perform(get("/api/v1/agencies").param("status", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail", Matchers.containsString("status")));
    }

    @Test
    void getAllReturns400WhenSortPropertyIsUnknown() throws Exception {
        mockMvc.perform(get("/api/v1/agencies").param("sort", "createdAtX,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Invalid sort property: createdAtX"));
    }

    @Test
    void getAllReturns400WhenSortDirectionIsInvalid() throws Exception {
        mockMvc.perform(get("/api/v1/agencies").param("sort", "code,BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void updateReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(agencyService.update(any(UUID.class), any(UpdateAgencyRequest.class))).thenReturn(sample(id));
        String body = objectMapper.writeValueAsString(new UpdateAgencyRequest("AG-PARIS-01", "Agency Paris Centre"));

        mockMvc.perform(put("/api/v1/agencies/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("AG-PARIS-01"));
    }

    @Test
    void updateReturns409WhenCodeUsedByAnotherAgency() throws Exception {
        UUID id = UUID.randomUUID();
        when(agencyService.update(any(UUID.class), any(UpdateAgencyRequest.class)))
                .thenThrow(new AgencyCodeAlreadyUsedException("AG-LYON-01"));
        String body = objectMapper.writeValueAsString(new UpdateAgencyRequest("AG-LYON-01", "Agency Paris 01"));

        mockMvc.perform(put("/api/v1/agencies/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Agency code already in use"));
    }

    @Test
    void deleteReturns204() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/agencies/{id}", id))
                .andExpect(status().isNoContent());

        verify(agencyService).deactivate(id);
    }

    @Test
    void activateReturns200WithActivatedAgency() throws Exception {
        UUID id = UUID.randomUUID();
        when(agencyService.activate(id)).thenReturn(sample(id));

        mockMvc.perform(post("/api/v1/agencies/{id}/activate", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void activateReturns404WhenUnknown() throws Exception {
        UUID id = UUID.randomUUID();
        when(agencyService.activate(id)).thenThrow(new AgencyNotFoundException(id));

        mockMvc.perform(post("/api/v1/agencies/{id}/activate", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Agency not found"));
    }

    @Test
    void unexpectedExceptionReturns500WithGenericMessage() throws Exception {
        UUID id = UUID.randomUUID();
        when(agencyService.getById(id)).thenThrow(new RuntimeException("sensitive internal detail"));

        mockMvc.perform(get("/api/v1/agencies/{id}", id))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.detail", Matchers.not(Matchers.containsString("sensitive"))));
    }
}
