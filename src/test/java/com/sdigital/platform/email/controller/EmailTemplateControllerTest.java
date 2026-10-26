package com.sdigital.platform.email.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdigital.platform.common.exception.GlobalExceptionHandler;
import com.sdigital.platform.email.dto.CreateEmailTemplateRequest;
import com.sdigital.platform.email.dto.EmailTemplateResponse;
import com.sdigital.platform.email.dto.UpdateEmailTemplateRequest;
import com.sdigital.platform.email.entity.EmailTemplateStatus;
import com.sdigital.platform.email.exception.EmailTemplateCodeAlreadyUsedException;
import com.sdigital.platform.email.exception.EmailTemplateNotFoundException;
import com.sdigital.platform.email.exception.InvalidEmailTemplateException;
import com.sdigital.platform.email.service.EmailTemplateService;
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

@WebMvcTest(controllers = EmailTemplateController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class EmailTemplateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private EmailTemplateService emailTemplateService;

    private EmailTemplateResponse sample(UUID id) {
        Instant created = Instant.parse("2026-01-01T10:00:00Z");
        return new EmailTemplateResponse(
                id, "USER_WELCOME", "Welcome email", "Welcome {{firstName}}",
                "Hello {{firstName}}", "fr", List.of("firstName"),
                EmailTemplateStatus.ACTIVE, created, created);
    }

    @Test
    void createReturns201WithBodyAndLocation() throws Exception {
        UUID id = UUID.randomUUID();
        when(emailTemplateService.create(any(CreateEmailTemplateRequest.class))).thenReturn(sample(id));
        String body = objectMapper.writeValueAsString(welcomeRequest());

        mockMvc.perform(post("/api/v1/email-templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", Matchers.endsWith("/api/v1/email-templates/" + id)))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.code").value("USER_WELCOME"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void createReturns400WhenBodyInvalid() throws Exception {
        String body = objectMapper.writeValueAsString(
                new CreateEmailTemplateRequest("", "", "", "", "xx-INVALID", List.of()));

        mockMvc.perform(post("/api/v1/email-templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void createReturns400WhenPlaceholdersAreInvalid() throws Exception {
        when(emailTemplateService.create(any(CreateEmailTemplateRequest.class)))
                .thenThrow(new InvalidEmailTemplateException("undeclared"));
        String body = objectMapper.writeValueAsString(welcomeRequest());

        mockMvc.perform(post("/api/v1/email-templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid email template placeholders"));
    }

    @Test
    void createReturns409WhenCodeAlreadyUsed() throws Exception {
        when(emailTemplateService.create(any(CreateEmailTemplateRequest.class)))
                .thenThrow(new EmailTemplateCodeAlreadyUsedException("USER_WELCOME"));
        String body = objectMapper.writeValueAsString(welcomeRequest());

        mockMvc.perform(post("/api/v1/email-templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Email template code already in use"));
    }

    @Test
    void getByIdReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(emailTemplateService.getById(id)).thenReturn(sample(id));

        mockMvc.perform(get("/api/v1/email-templates/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("USER_WELCOME"));
    }

    @Test
    void getByIdReturns404WhenUnknown() throws Exception {
        UUID id = UUID.randomUUID();
        when(emailTemplateService.getById(id)).thenThrow(new EmailTemplateNotFoundException(id));

        mockMvc.perform(get("/api/v1/email-templates/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Email template not found"));
    }

    @Test
    void getAllPassesFiltersToService() throws Exception {
        when(emailTemplateService.getAll(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 5), 0));

        mockMvc.perform(get("/api/v1/email-templates")
                        .param("search", "welcome")
                        .param("status", "ACTIVE")
                        .param("locale", "fr")
                        .param("page", "1")
                        .param("size", "5")
                        .param("sort", "code,asc"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> search = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<EmailTemplateStatus> status = ArgumentCaptor.forClass(EmailTemplateStatus.class);
        ArgumentCaptor<String> locale = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(emailTemplateService).getAll(search.capture(), status.capture(), locale.capture(), pageable.capture());
        assertThat(search.getValue()).isEqualTo("welcome");
        assertThat(status.getValue()).isEqualTo(EmailTemplateStatus.ACTIVE);
        assertThat(locale.getValue()).isEqualTo("fr");
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "code"));
    }

    @Test
    void getAllReturns400WhenSortPropertyIsUnknown() throws Exception {
        mockMvc.perform(get("/api/v1/email-templates").param("sort", "body,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid sort property: body"));
    }

    @Test
    void updateReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(emailTemplateService.update(any(UUID.class), any(UpdateEmailTemplateRequest.class)))
                .thenReturn(sample(id));
        String body = objectMapper.writeValueAsString(new UpdateEmailTemplateRequest(
                "USER_WELCOME", "Welcome email", "Welcome {{firstName}}",
                "Hello {{firstName}}", "fr", List.of("firstName")));

        mockMvc.perform(put("/api/v1/email-templates/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("USER_WELCOME"));
    }

    @Test
    void deleteReturns204() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/email-templates/{id}", id))
                .andExpect(status().isNoContent());

        verify(emailTemplateService).deactivate(id);
    }

    @Test
    void activateReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(emailTemplateService.activate(id)).thenReturn(sample(id));

        mockMvc.perform(post("/api/v1/email-templates/{id}/activate", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    private static CreateEmailTemplateRequest welcomeRequest() {
        return new CreateEmailTemplateRequest(
                "USER_WELCOME", "Welcome email", "Welcome {{firstName}}",
                "Hello {{firstName}}", "fr", List.of("firstName"));
    }
}
