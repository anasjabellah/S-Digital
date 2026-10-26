package com.sdigital.platform.email.service;

import com.sdigital.platform.audit.entity.AuditOutcome;
import com.sdigital.platform.audit.service.AuditChanges;
import com.sdigital.platform.audit.service.AuditLogService;
import com.sdigital.platform.common.security.CurrentActorResolver;
import com.sdigital.platform.email.dto.CreateEmailTemplateRequest;
import com.sdigital.platform.email.dto.EmailTemplateResponse;
import com.sdigital.platform.email.dto.UpdateEmailTemplateRequest;
import com.sdigital.platform.email.entity.EmailTemplate;
import com.sdigital.platform.email.entity.EmailTemplateStatus;
import com.sdigital.platform.email.exception.EmailTemplateCodeAlreadyUsedException;
import com.sdigital.platform.email.exception.EmailTemplateNotFoundException;
import com.sdigital.platform.email.mapper.EmailTemplateMapper;
import com.sdigital.platform.email.repository.EmailTemplateRepository;
import com.sdigital.platform.email.repository.EmailTemplateSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Business operations for the email-template module.
 *
 * <p>Responsibilities: code normalization, uniqueness, placeholder validation and
 * status transitions. Persistence stays in the repository, mapping stays in the mapper.
 * This service never sends email.</p>
 */
@Service
public class EmailTemplateService {

    private static final String AUDIT_ENTITY = "EMAIL_TEMPLATE";
    private static final String ACTION_CREATED = "EMAIL_TEMPLATE_CREATED";
    private static final String ACTION_UPDATED = "EMAIL_TEMPLATE_UPDATED";
    private static final String ACTION_DEACTIVATED = "EMAIL_TEMPLATE_DEACTIVATED";
    private static final String ACTION_ACTIVATED = "EMAIL_TEMPLATE_ACTIVATED";

    private final EmailTemplateRepository emailTemplateRepository;
    private final EmailTemplateMapper emailTemplateMapper;
    private final AuditLogService auditLogService;
    private final CurrentActorResolver currentActorResolver;

    public EmailTemplateService(EmailTemplateRepository emailTemplateRepository,
                                EmailTemplateMapper emailTemplateMapper,
                                AuditLogService auditLogService,
                                CurrentActorResolver currentActorResolver) {
        this.emailTemplateRepository = emailTemplateRepository;
        this.emailTemplateMapper = emailTemplateMapper;
        this.auditLogService = auditLogService;
        this.currentActorResolver = currentActorResolver;
    }

    @Transactional
    public EmailTemplateResponse create(CreateEmailTemplateRequest request) {
        CreateEmailTemplateRequest normalized = normalize(request);
        ensureCodeAvailable(normalized.code(), null);
        EmailTemplate template = emailTemplateMapper.toEntity(normalized);
        EmailTemplate saved = emailTemplateRepository.save(template);
        auditLogService.record(resolveActorUserId(), ACTION_CREATED, AUDIT_ENTITY,
                entityIdOf(saved.getId()), AuditOutcome.SUCCESS, Map.of("status", saved.getStatus().name()));
        return emailTemplateMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public EmailTemplateResponse getById(UUID id) {
        return emailTemplateMapper.toResponse(findTemplateOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Page<EmailTemplateResponse> getAll(Pageable pageable) {
        return emailTemplateRepository.findAll(pageable).map(emailTemplateMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<EmailTemplateResponse> getAll(String search,
                                              EmailTemplateStatus status,
                                              String locale,
                                              Pageable pageable) {
        if (isBlank(search) && status == null && isBlank(locale)) {
            return getAll(pageable);
        }
        return emailTemplateRepository
                .findAll(EmailTemplateSpecifications.withFilters(search, status, locale), pageable)
                .map(emailTemplateMapper::toResponse);
    }

    @Transactional
    public EmailTemplateResponse update(UUID id, UpdateEmailTemplateRequest request) {
        EmailTemplate template = findTemplateOrThrow(id);
        UpdateEmailTemplateRequest normalized = normalize(request);
        ensureCodeAvailable(normalized.code(), id);
        AuditChanges changes = new AuditChanges()
                .track("code", template.getCode(), normalized.code())
                .track("name", template.getName(), normalized.name())
                .track("subject", template.getSubject(), normalized.subject())
                .track("body", template.getBody(), normalized.body())
                .track("locale", template.getLocale(), normalized.locale())
                .track("variables", String.valueOf(template.getVariables()), String.valueOf(normalized.variables()));
        emailTemplateMapper.applyUpdate(template, normalized);
        EmailTemplate saved = emailTemplateRepository.save(template);
        if (!changes.isEmpty()) {
            auditLogService.record(resolveActorUserId(), ACTION_UPDATED, AUDIT_ENTITY,
                    entityIdOf(saved.getId()), AuditOutcome.SUCCESS, Map.of("fields", changes.fields()));
        }
        return emailTemplateMapper.toResponse(saved);
    }

    @Transactional
    public EmailTemplateResponse deactivate(UUID id) {
        EmailTemplate template = findTemplateOrThrow(id);
        EmailTemplateStatus previousStatus = template.getStatus();
        if (previousStatus == EmailTemplateStatus.INACTIVE) {
            return emailTemplateMapper.toResponse(template);
        }
        template.setStatus(EmailTemplateStatus.INACTIVE);
        EmailTemplate saved = emailTemplateRepository.save(template);
        auditLogService.record(resolveActorUserId(), ACTION_DEACTIVATED, AUDIT_ENTITY,
                entityIdOf(saved.getId()), AuditOutcome.SUCCESS,
                Map.of("previousStatus", previousStatus.name(), "status", saved.getStatus().name()));
        return emailTemplateMapper.toResponse(saved);
    }

    @Transactional
    public EmailTemplateResponse activate(UUID id) {
        EmailTemplate template = findTemplateOrThrow(id);
        EmailTemplateStatus previousStatus = template.getStatus();
        if (previousStatus == EmailTemplateStatus.ACTIVE) {
            return emailTemplateMapper.toResponse(template);
        }
        template.setStatus(EmailTemplateStatus.ACTIVE);
        EmailTemplate saved = emailTemplateRepository.save(template);
        auditLogService.record(resolveActorUserId(), ACTION_ACTIVATED, AUDIT_ENTITY,
                entityIdOf(saved.getId()), AuditOutcome.SUCCESS,
                Map.of("previousStatus", previousStatus.name(), "status", saved.getStatus().name()));
        return emailTemplateMapper.toResponse(saved);
    }

    private EmailTemplate findTemplateOrThrow(UUID id) {
        return emailTemplateRepository.findById(id)
                .orElseThrow(() -> new EmailTemplateNotFoundException(id));
    }

    private UUID resolveActorUserId() {
        return currentActorResolver.resolveCurrentActorUserId();
    }

    private static String entityIdOf(UUID id) {
        return id == null ? null : id.toString();
    }

    private void ensureCodeAvailable(String code, UUID excludedId) {
        boolean alreadyUsed = excludedId == null
                ? emailTemplateRepository.existsByCode(code)
                : emailTemplateRepository.existsByCodeAndIdNot(code, excludedId);
        if (alreadyUsed) {
            throw new EmailTemplateCodeAlreadyUsedException(code);
        }
    }

    private CreateEmailTemplateRequest normalize(CreateEmailTemplateRequest request) {
        List<String> variables = EmailTemplateVariables.normalizeDeclared(request.variables());
        String subject = request.subject().trim();
        String body = request.body().trim();
        EmailTemplateVariables.validatePlaceholders(subject, body, variables);
        return new CreateEmailTemplateRequest(
                normalizeCode(request.code()),
                normalizeName(request.name()),
                subject,
                body,
                request.locale().trim(),
                variables);
    }

    private UpdateEmailTemplateRequest normalize(UpdateEmailTemplateRequest request) {
        List<String> variables = EmailTemplateVariables.normalizeDeclared(request.variables());
        String subject = request.subject().trim();
        String body = request.body().trim();
        EmailTemplateVariables.validatePlaceholders(subject, body, variables);
        return new UpdateEmailTemplateRequest(
                normalizeCode(request.code()),
                normalizeName(request.name()),
                subject,
                body,
                request.locale().trim(),
                variables);
    }

    private static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeName(String name) {
        return name.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
