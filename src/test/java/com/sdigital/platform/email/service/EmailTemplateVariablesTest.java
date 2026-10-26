package com.sdigital.platform.email.service;

import com.sdigital.platform.email.exception.InvalidEmailTemplateException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailTemplateVariablesTest {

    @Test
    void acceptsDeclaredPlaceholders() {
        List<String> declared = EmailTemplateVariables.normalizeDeclared(List.of("firstName", " lastName "));
        EmailTemplateVariables.validatePlaceholders(
                "Hello {{firstName}}", "Dear {{ firstName }} {{lastName}}", declared);

        assertThat(declared).containsExactly("firstName", "lastName");
    }

    @Test
    void rejectsUndeclaredPlaceholder() {
        List<String> declared = EmailTemplateVariables.normalizeDeclared(List.of("firstName"));

        assertThatThrownBy(() -> EmailTemplateVariables.validatePlaceholders(
                "Hello {{unknown}}", "Body", declared))
                .isInstanceOf(InvalidEmailTemplateException.class);
    }

    @Test
    void rejectsMalformedPlaceholder() {
        List<String> declared = EmailTemplateVariables.normalizeDeclared(List.of("firstName"));

        assertThatThrownBy(() -> EmailTemplateVariables.validatePlaceholders(
                "Hello {{first.name}}", "Body", declared))
                .isInstanceOf(InvalidEmailTemplateException.class);
    }

    @Test
    void rejectsInterpolationSyntax() {
        List<String> declared = EmailTemplateVariables.normalizeDeclared(List.of());

        assertThatThrownBy(() -> EmailTemplateVariables.validatePlaceholders(
                "Hello ${name}", "Body", declared))
                .isInstanceOf(InvalidEmailTemplateException.class);
    }

    @Test
    void rejectsDuplicateDeclaredNames() {
        assertThatThrownBy(() -> EmailTemplateVariables.normalizeDeclared(List.of("firstName", "firstName")))
                .isInstanceOf(InvalidEmailTemplateException.class);
    }
}
