package com.sdigital.platform.audit.service;

import com.sdigital.platform.audit.dto.AuditLogFilter;
import com.sdigital.platform.audit.dto.AuditLogResponse;
import com.sdigital.platform.audit.mapper.AuditLogMapper;
import com.sdigital.platform.audit.repository.AuditLogRepository;
import com.sdigital.platform.audit.repository.AuditLogSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only service for the consultation of the audit trail.
 *
 * <p>It is the query counterpart of {@link AuditLogService}, which only writes events.
 * The two are kept apart so the write path keeps its documented contract (append-only,
 * transactional join) and can never be used to alter an existing entry.</p>
 *
 * <p>The listing is performed by the database: the criteria are translated into SQL
 * predicates and the page is selected with a real {@code LIMIT}/{@code OFFSET}, so the
 * whole table is never loaded in memory. The total number of matching rows is computed
 * by a separate count query on the same predicates, so the reported total is always
 * consistent with the returned page.</p>
 */
@Service
public class AuditLogQueryService {

    /**
     * Secondary sort applied to every listing. The id is unique and immutable, so
     * appending it to the requested sort makes the order total: two events sharing the
     * same sort key (typically the same {@code occurredAt}) can no longer swap places
     * between two pages, and the pagination is therefore stable.
     */
    private static final Sort STABLE_TIE_BREAKER = Sort.by(Sort.Direction.DESC, "id");

    private final AuditLogRepository auditLogRepository;
    private final AuditLogMapper auditLogMapper;

    public AuditLogQueryService(AuditLogRepository auditLogRepository, AuditLogMapper auditLogMapper) {
        this.auditLogRepository = auditLogRepository;
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * Returns a page of audit events matching the given criteria.
     *
     * @param filter   the criteria; null matches every event
     * @param pageable the pagination and the (already validated) sort
     * @return the matching page, mapped to the read model
     */
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> search(AuditLogFilter filter, Pageable pageable) {
        return auditLogRepository.findAll(AuditLogSpecifications.withFilters(filter), withStableOrder(pageable))
                .map(auditLogMapper::toResponse);
    }

    /**
     * Appends the deterministic tie-breaker to the requested sort, keeping the page
     * number and the page size untouched.
     */
    private static Pageable withStableOrder(Pageable pageable) {
        if (pageable.isUnpaged()) {
            return pageable;
        }
        return PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                pageable.getSort().and(STABLE_TIE_BREAKER));
    }
}
