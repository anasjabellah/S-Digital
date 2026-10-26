package com.sdigital.platform.audit.repository;

import com.sdigital.platform.audit.dto.AuditLogFilter;
import com.sdigital.platform.audit.entity.AuditLog;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Dynamic {@link Specification} factory for {@link AuditLog} filtering.
 *
 * <p>Mirrors the convention already used for the user listing
 * ({@code UserSpecifications}): every criterion is optional, the criteria are combined
 * with a conjunction and are translated into SQL predicates, so the rows are filtered
 * by the database and never loaded in memory.</p>
 *
 * <p>The time bounds are inclusive on both ends ({@code occurredAt >= from} and
 * {@code occurredAt <= to}).</p>
 */
public final class AuditLogSpecifications {

    private AuditLogSpecifications() {
    }

    /**
     * Builds the specification matching the given criteria.
     *
     * @param filter the criteria; a null filter matches every event
     */
    public static Specification<AuditLog> withFilters(AuditLogFilter filter) {
        if (filter == null) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.action() != null) {
                predicates.add(cb.equal(root.get("action"), filter.action()));
            }
            if (filter.entityType() != null) {
                predicates.add(cb.equal(root.get("entityType"), filter.entityType()));
            }
            if (filter.entityId() != null) {
                predicates.add(cb.equal(root.get("entityId"), filter.entityId().toString()));
            }
            if (filter.actorUserId() != null) {
                predicates.add(cb.equal(root.get("actorUserId"), filter.actorUserId()));
            }
            if (filter.outcome() != null) {
                predicates.add(cb.equal(root.get("outcome"), filter.outcome()));
            }
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("occurredAt"), filter.to()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
