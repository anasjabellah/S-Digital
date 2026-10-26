package com.sdigital.platform.email.service;

import com.sdigital.platform.email.exception.InvalidEmailTemplateException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates declared placeholders and their use in subject/body.
 *
 * <p>The supported format is Mustache-like {@code {{variableName}}}. Expressions,
 * nested braces, dotted paths and {@code ${...}} interpolation are rejected so a
 * stored template cannot execute logic when it is later rendered.</p>
 */
public final class EmailTemplateVariables {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*)\\s*\\}\\}");
    private static final Pattern VARIABLE_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    private EmailTemplateVariables() {
    }

    public static List<String> normalizeDeclared(List<String> declared) {
        if (declared == null || declared.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String raw : declared) {
            if (raw == null || raw.isBlank()) {
                throw new InvalidEmailTemplateException("Template variable names must not be blank");
            }
            String name = raw.trim();
            if (!VARIABLE_NAME.matcher(name).matches()) {
                throw new InvalidEmailTemplateException("Template variable names must be identifiers");
            }
            if (!unique.add(name)) {
                throw new InvalidEmailTemplateException("Template variable names must be unique");
            }
        }
        return List.copyOf(unique);
    }

    public static void validatePlaceholders(String subject, String body, List<String> declared) {
        Set<String> allowed = Set.copyOf(declared);
        validateText(subject, allowed);
        validateText(body, allowed);
    }

    private static void validateText(String text, Set<String> allowed) {
        if (text == null) {
            return;
        }
        if (text.contains("${")) {
            throw new InvalidEmailTemplateException("Template must not contain interpolation expressions");
        }

        List<String> used = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuffer withoutValid = new StringBuffer();
        while (matcher.find()) {
            used.add(matcher.group(1));
            matcher.appendReplacement(withoutValid, "");
        }
        matcher.appendTail(withoutValid);

        String remainder = withoutValid.toString();
        if (remainder.contains("{{") || remainder.contains("}}")) {
            throw new InvalidEmailTemplateException("Template placeholders must use {{variableName}}");
        }

        for (String name : used) {
            if (!allowed.contains(name)) {
                throw new InvalidEmailTemplateException(
                        "Template placeholder is not declared: " + name.toLowerCase(Locale.ROOT));
            }
        }
    }
}
