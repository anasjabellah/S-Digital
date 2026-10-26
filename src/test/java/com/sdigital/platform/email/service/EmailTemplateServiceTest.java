package com.sdigital.platform.email.service;

import com.sdigital.platform.audit.entity.AuditOutcome;
import com.sdigital.platform.audit.service.AuditLogService;
import com.sdigital.platform.common.security.CurrentActorResolver;
import com.sdigital.platform.email.EmailTemplateTestData;
import com.sdigital.platform.email.dto.CreateEmailTemplateRequest;
import com.sdigital.platform.email.dto.EmailTemplateResponse;
import com.sdigital.platform.email.dto.UpdateEmailTemplateRequest;
import com.sdigital.platform.email.entity.EmailTemplate;
import com.sdigital.platform.email.entity.EmailTemplateStatus;
import com.sdigital.platform.email.exception.EmailTemplateCodeAlreadyUsedException;
import com.sdigital.platform.email.exception.EmailTemplateNotFoundException;
import com.sdigital.platform.email.exception.InvalidEmailTemplateException;
import com.sdigital.platform.email.mapper.EmailTemplateMapper;
import com.sdigital.platform.email.repository.EmailTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailTemplateServiceTest {

    private static final UUID ACTOR_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private EmailTemplateRepository emailTemplateRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CurrentActorResolver currentActorResolver;

    private EmailTemplateService emailTemplateService;

    @BeforeEach
    void setUp() {
        emailTemplateService = new EmailTemplateService(
                emailTemplateRepository, new EmailTemplateMapper(), auditLogService, currentActorResolver);
    }

    @Test
    void createNormalizesCodeAndPersists() {
        CreateEmailTemplateRequest request = new CreateEmailTemplateRequest(
                "  user_welcome ", "  Welcome email ", "Welcome {{firstName}}",
                "Hello {{firstName}}", "fr", List.of("firstName"));
        when(emailTemplateRepository.existsByCode("USER_WELCOME")).thenReturn(false);
        when(emailTemplateRepository.save(any(EmailTemplate.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EmailTemplateResponse response = emailTemplateService.create(request);

        assertThat(response.code()).isEqualTo("USER_WELCOME");
        assertThat(response.name()).isEqualTo("Welcome email");
        assertThat(response.status()).isEqualTo(EmailTemplateStatus.ACTIVE);
        assertThat(response.variables()).containsExactly("firstName");
        verify(emailTemplateRepository).save(any(EmailTemplate.class));
    }

    @Test
    void createRejectsAlreadyUsedCode() {
        CreateEmailTemplateRequest request = welcomeRequest();
        when(emailTemplateRepository.existsByCode("USER_WELCOME")).thenReturn(true);

        assertThatThrownBy(() -> emailTemplateService.create(request))
                .isInstanceOf(EmailTemplateCodeAlreadyUsedException.class);

        verify(emailTemplateRepository, never()).save(any(EmailTemplate.class));
    }

    @Test
    void createRejectsUndeclaredPlaceholder() {
        CreateEmailTemplateRequest request = new CreateEmailTemplateRequest(
                "USER_WELCOME", "Welcome email", "Hello {{unknown}}", "Body", "fr", List.of("firstName"));

        assertThatThrownBy(() -> emailTemplateService.create(request))
                .isInstanceOf(InvalidEmailTemplateException.class);

        verify(emailTemplateRepository, never()).save(any(EmailTemplate.class));
    }

    @Test
    void getByIdThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(emailTemplateRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> emailTemplateService.getById(id))
                .isInstanceOf(EmailTemplateNotFoundException.class);
    }

    @Test
    void updateAllowsUnchangedCodeOfSameTemplate() {
        UUID id = UUID.randomUUID();
        EmailTemplate template = EmailTemplateTestData.template(
                id, "USER_WELCOME", "Welcome email", Instant.now(), Instant.now());
        when(emailTemplateRepository.findById(id)).thenReturn(Optional.of(template));
        when(emailTemplateRepository.existsByCodeAndIdNot("USER_WELCOME", id)).thenReturn(false);
        when(emailTemplateRepository.save(any(EmailTemplate.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EmailTemplateResponse response = emailTemplateService.update(id, new UpdateEmailTemplateRequest(
                "  user_welcome ", " Welcome email v2 ", "Welcome {{firstName}}",
                "Hello {{firstName}}", "fr", List.of("firstName")));

        assertThat(response.name()).isEqualTo("Welcome email v2");
        verify(emailTemplateRepository).save(template);
    }

    @Test
    void deactivateSetsStatusInactiveWithoutDeleting() {
        UUID id = UUID.randomUUID();
        EmailTemplate template = EmailTemplateTestData.template(
                id, "USER_WELCOME", "Welcome email", Instant.now(), Instant.now());
        when(emailTemplateRepository.findById(id)).thenReturn(Optional.of(template));
        when(emailTemplateRepository.save(any(EmailTemplate.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EmailTemplateResponse response = emailTemplateService.deactivate(id);

        assertThat(response.status()).isEqualTo(EmailTemplateStatus.INACTIVE);
        verify(emailTemplateRepository, never()).deleteById(any(UUID.class));
    }

    @Test
    void activateRestoresActiveStatus() {
        UUID id = UUID.randomUUID();
        EmailTemplate template = EmailTemplateTestData.template(
                id, "USER_WELCOME", "Welcome email", Instant.now(), Instant.now());
        template.setStatus(EmailTemplateStatus.INACTIVE);
        when(emailTemplateRepository.findById(id)).thenReturn(Optional.of(template));
        when(emailTemplateRepository.save(any(EmailTemplate.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EmailTemplateResponse response = emailTemplateService.activate(id);

        assertThat(response.status()).isEqualTo(EmailTemplateStatus.ACTIVE);
    }

    @Test
    void getAllWithoutFiltersKeepsUnfilteredQuery() {
        Pageable pageable = PageRequest.of(0, 10);
        when(emailTemplateRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        emailTemplateService.getAll(null, null, null, pageable);

        verify(emailTemplateRepository).findAll(pageable);
        verify(emailTemplateRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void getAllWithSearchUsesFilteredQuery() {
        Pageable pageable = PageRequest.of(0, 10);
        EmailTemplate template = EmailTemplateTestData.template(
                UUID.randomUUID(), "USER_WELCOME", "Welcome email", Instant.now(), Instant.now());
        when(emailTemplateRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(template), pageable, 1));

        Page<EmailTemplateResponse> page = emailTemplateService.getAll("welcome", null, null, pageable);

        assertThat(page.getContent()).hasSize(1);
        verify(emailTemplateRepository, never()).findAll(pageable);
    }

    @Test
    void createAuditsEmailTemplateCreated() {
        UUID templateId = UUID.randomUUID();
        when(emailTemplateRepository.existsByCode("USER_WELCOME")).thenReturn(false);
        when(emailTemplateRepository.save(any(EmailTemplate.class))).thenAnswer(invocation -> {
            EmailTemplate saved = invocation.getArgument(0);
            EmailTemplateTestData.setId(saved, templateId);
            return saved;
        });
        when(currentActorResolver.resolveCurrentActorUserId()).thenReturn(ACTOR_ID);

        emailTemplateService.create(welcomeRequest());

        verify(auditLogService).record(eq(ACTOR_ID), eq("EMAIL_TEMPLATE_CREATED"), eq("EMAIL_TEMPLATE"),
                eq(templateId.toString()), eq(AuditOutcome.SUCCESS), anyMap());
    }

    @Test
    void updateAuditsChangedFields() {
        UUID templateId = UUID.randomUUID();
        when(emailTemplateRepository.findById(templateId)).thenReturn(Optional.of(
                EmailTemplateTestData.template(templateId, "USER_WELCOME", "Welcome email",
                        Instant.now(), Instant.now())));
        when(emailTemplateRepository.existsByCodeAndIdNot("USER_WELCOME_V2", templateId)).thenReturn(false);
        when(emailTemplateRepository.save(any(EmailTemplate.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(currentActorResolver.resolveCurrentActorUserId()).thenReturn(ACTOR_ID);

        emailTemplateService.update(templateId, new UpdateEmailTemplateRequest(
                "USER_WELCOME_V2", "Welcome email v2", "Welcome {{firstName}}",
                "Hello {{firstName}}", "fr", List.of("firstName")));

        verify(auditLogService).record(eq(ACTOR_ID), eq("EMAIL_TEMPLATE_UPDATED"), eq("EMAIL_TEMPLATE"),
                eq(templateId.toString()), eq(AuditOutcome.SUCCESS),
                eq(Map.of("fields", List.of("code", "name"))));
    }

    private static CreateEmailTemplateRequest welcomeRequest() {
        return new CreateEmailTemplateRequest(
                "USER_WELCOME", "Welcome email", "Welcome {{firstName}}",
                "Hello {{firstName}}", "fr", List.of("firstName"));
    }
}
