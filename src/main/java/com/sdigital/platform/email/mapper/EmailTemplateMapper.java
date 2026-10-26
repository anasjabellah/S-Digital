package com.sdigital.platform.email.mapper;

import com.sdigital.platform.email.dto.CreateEmailTemplateRequest;
import com.sdigital.platform.email.dto.EmailTemplateResponse;
import com.sdigital.platform.email.dto.UpdateEmailTemplateRequest;
import com.sdigital.platform.email.entity.EmailTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Manual mapper between the {@link EmailTemplate} entity and its DTOs.
 *
 * <p>Pure translation only: no persistence access and no business logic (code
 * normalization and placeholder checks belong to the service).</p>
 */
@Component
public class EmailTemplateMapper {

    public EmailTemplateResponse toResponse(EmailTemplate template) {
        List<String> variables = template.getVariables() == null
                ? List.of()
                : List.copyOf(template.getVariables());
        return new EmailTemplateResponse(
                template.getId(),
                template.getCode(),
                template.getName(),
                template.getSubject(),
                template.getBody(),
                template.getLocale(),
                variables,
                template.getStatus(),
                template.getCreatedAt(),
                template.getUpdatedAt()
        );
    }

    public EmailTemplate toEntity(CreateEmailTemplateRequest request) {
        return new EmailTemplate(
                request.code(),
                request.name(),
                request.subject(),
                request.body(),
                request.locale(),
                request.variables());
    }

    public void applyUpdate(EmailTemplate template, UpdateEmailTemplateRequest request) {
        template.setCode(request.code());
        template.setName(request.name());
        template.setSubject(request.subject());
        template.setBody(request.body());
        template.setLocale(request.locale());
        template.setVariables(request.variables());
    }
}
