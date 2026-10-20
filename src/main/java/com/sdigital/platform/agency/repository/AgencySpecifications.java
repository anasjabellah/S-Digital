package com.sdigital.platform.agency.repository;

import com.sdigital.platform.agency.entity.Agency;
import com.sdigital.platform.agency.entity.AgencyStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dynamic {@link Specification} factory for {@link Agency} filtering.
 *
 * <p>Supports an optional case-insensitive partial match on code and name, and an
 * optional exact status match. SQL wildcards contained in the search term are escaped
 * so they match literally. Both filters can be combined; when none is provided the
 * resulting specification matches every agency.</p>
 */
public final class AgencySpecifications {

    private AgencySpecifications() {
    }

    public static Specification<Agency> withFilters(String search, AgencyStatus status) {
        String term = normalizeSearch(search);
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
