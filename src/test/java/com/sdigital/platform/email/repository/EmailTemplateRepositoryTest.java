package com.sdigital.platform.email.repository;

import com.sdigital.platform.email.entity.EmailTemplate;
import com.sdigital.platform.email.entity.EmailTemplateStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class EmailTemplateRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private EmailTemplateRepository emailTemplateRepository;

    @Test
    void savesTemplateWithGeneratedIdDefaultsAndTimestamps() {
        EmailTemplate saved = emailTemplateRepository.saveAndFlush(welcome());

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(EmailTemplateStatus.ACTIVE);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getVariables()).containsExactly("firstName");
    }

    @Test
    void rejectsDuplicateCode() {
        emailTemplateRepository.saveAndFlush(welcome());

        assertThatThrownBy(() -> emailTemplateRepository.saveAndFlush(
                new EmailTemplate("USER_WELCOME", "Other", "S", "B", "en", List.of())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void existsByCodeAndIdNotIgnoresCurrentRow() {
        EmailTemplate saved = emailTemplateRepository.saveAndFlush(welcome());

        assertThat(emailTemplateRepository.existsByCode("USER_WELCOME")).isTrue();
        assertThat(emailTemplateRepository.existsByCodeAndIdNot("USER_WELCOME", saved.getId())).isFalse();
    }

    @Test
    void filtersBySearchAndStatus() {
        emailTemplateRepository.saveAndFlush(welcome());
        emailTemplateRepository.saveAndFlush(
                new EmailTemplate("PASSWORD_RESET", "Reset", "Reset", "Body", "fr", List.of()));

        Page<EmailTemplate> page = emailTemplateRepository.findAll(
                EmailTemplateSpecifications.withFilters("welcome", EmailTemplateStatus.ACTIVE, "fr"),
                PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(EmailTemplate::getCode).containsExactly("USER_WELCOME");
    }

    private static EmailTemplate welcome() {
        return new EmailTemplate(
                "USER_WELCOME", "Welcome email", "Welcome {{firstName}}",
                "Hello {{firstName}}", "fr", List.of("firstName"));
    }
}
