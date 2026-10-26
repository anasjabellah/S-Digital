package com.sdigital.platform.audit.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Collects the names of the fields actually modified by a mutation.
 *
 * <p>The persistent audit must describe real changes: a field that is written with the value
 * it already had is not a modification and must not appear in the audit details. This helper
 * compares the value before and after the mutation and keeps only the fields that differ.</p>
 *
 * <p>Instances are mutable and used inside a single service call; they are not thread-safe
 * and are never shared between requests.</p>
 */
public final class AuditChanges {

    private final List<String> changedFields = new ArrayList<>();

    /**
     * Registers a field and records it as modified when the value actually differs.
     *
     * @param field  stable field name used in the audit details
     * @param before value before the mutation
     * @param after  value after the mutation
     * @return this instance, for chaining
     */
    public AuditChanges track(String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changedFields.add(field);
        }
        return this;
    }

    /**
     * Returns {@code true} when no field was actually modified.
     */
    public boolean isEmpty() {
        return changedFields.isEmpty();
    }

    /**
     * Returns the names of the modified fields, in registration order.
     */
    public List<String> fields() {
        return List.copyOf(changedFields);
    }
}
