package com.sdigital.platform.email.repository;

import com.sdigital.platform.email.entity.EmailTemplate;
import com.sdigital.platform.email.entity.EmailTemplateStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dynamic {@link Specification} factory for {@link EmailTemplate} filtering.
 *
 * <p>Supports an optional case-insensitive partial match on code and name, an optional
 * exact status match and an optional exact locale match. SQL wildcards contained in the
 * search term are escaped so they match literally.</p>
 */
public final class EmailTemplateSpecifications {

    private EmailTemplateSpecifications() {
    }

    public static Specification<EmailTemplate> withFilters(String search,
                                                           EmailTemplateStatus status,
                                                           String locale) {
        String term = normalizeSearch(search);
        String localeFilter = normalizeSearch(locale);
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (term != null) {
                String pattern = "%" + escapeLike(term.toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("code")), pattern, '\\'),
                        cb.like(cb.lower(root.get("name")), pattern, '\\')));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (localeFilter != null) {
                predicates.add(cb.equal(root.get("locale"), localeFilter));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static String normalizeSearch(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        return search.trim();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
