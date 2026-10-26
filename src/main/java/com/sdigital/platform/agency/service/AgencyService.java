package com.sdigital.platform.agency.service;

import com.sdigital.platform.agency.dto.AgencyResponse;
import com.sdigital.platform.agency.dto.CreateAgencyRequest;
import com.sdigital.platform.agency.dto.UpdateAgencyRequest;
import com.sdigital.platform.agency.entity.Agency;
import com.sdigital.platform.agency.entity.AgencyStatus;
import com.sdigital.platform.agency.exception.AgencyCodeAlreadyUsedException;
import com.sdigital.platform.agency.exception.AgencyNotFoundException;
import com.sdigital.platform.agency.mapper.AgencyMapper;
import com.sdigital.platform.agency.repository.AgencyRepository;
import com.sdigital.platform.agency.repository.AgencySpecifications;
import com.sdigital.platform.audit.entity.AuditOutcome;
import com.sdigital.platform.audit.service.AuditChanges;
import com.sdigital.platform.audit.service.AuditLogService;
import com.sdigital.platform.common.security.CurrentActorResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Business operations for the agency module.
 *
 * <p>Responsibilities: code normalization (trim + uppercase), name trimming, uniqueness
 * enforcement and status transitions. Persistence stays in the repository, mapping stays
 * in the mapper.</p>
 */
@Service
public class AgencyService {

    private static final String AUDIT_ENTITY_AGENCY = "AGENCY";

    private static final String ACTION_AGENCY_CREATED = "AGENCY_CREATED";
    private static final String ACTION_AGENCY_UPDATED = "AGENCY_UPDATED";
    private static final String ACTION_AGENCY_DEACTIVATED = "AGENCY_DEACTIVATED";
    private static final String ACTION_AGENCY_ACTIVATED = "AGENCY_ACTIVATED";

    private final AgencyRepository agencyRepository;
    private final AgencyMapper agencyMapper;
    private final AuditLogService auditLogService;
    private final CurrentActorResolver currentActorResolver;

    public AgencyService(AgencyRepository agencyRepository,
                         AgencyMapper agencyMapper,
                         AuditLogService auditLogService,
                         CurrentActorResolver currentActorResolver) {
        this.agencyRepository = agencyRepository;
        this.agencyMapper = agencyMapper;
        this.auditLogService = auditLogService;
        this.currentActorResolver = currentActorResolver;
    }

    @Transactional
    public AgencyResponse create(CreateAgencyRequest request) {
        CreateAgencyRequest normalized = normalize(request);
        ensureCodeAvailable(normalized.code(), null);
        Agency agency = agencyMapper.toEntity(normalized);
        Agency saved = agencyRepository.save(agency);
        auditLogService.record(resolveActorUserId(), ACTION_AGENCY_CREATED, AUDIT_ENTITY_AGENCY,
                entityIdOf(saved.getId()), AuditOutcome.SUCCESS, Map.of("status", saved.getStatus().name()));
        return agencyMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public AgencyResponse getById(UUID id) {
        return agencyMapper.toResponse(findAgencyOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Page<AgencyResponse> getAll(Pageable pageable) {
        return agencyRepository.findAll(pageable).map(agencyMapper::toResponse);
    }

    /**
     * Returns a page of agencies narrowed by optional filters. A blank search is treated
     * as absent; when both filters are absent the historical unfiltered listing is used.
     */
    @Transactional(readOnly = true)
    public Page<AgencyResponse> getAll(String search, AgencyStatus status, Pageable pageable) {
        if (isBlank(search) && status == null) {
            return getAll(pageable);
        }
        return agencyRepository.findAll(AgencySpecifications.withFilters(search, status), pageable)
                .map(agencyMapper::toResponse);
    }

    @Transactional
    public AgencyResponse update(UUID id, UpdateAgencyRequest request) {
        Agency agency = findAgencyOrThrow(id);
        UpdateAgencyRequest normalized = normalize(request);
        ensureCodeAvailable(normalized.code(), id);
        AuditChanges changes = new AuditChanges()
                .track("code", agency.getCode(), normalized.code())
                .track("name", agency.getName(), normalized.name());
        agencyMapper.applyUpdate(agency, normalized);
        Agency saved = agencyRepository.save(agency);
        if (!changes.isEmpty()) {
            auditLogService.record(resolveActorUserId(), ACTION_AGENCY_UPDATED, AUDIT_ENTITY_AGENCY,
                    entityIdOf(saved.getId()), AuditOutcome.SUCCESS, Map.of("fields", changes.fields()));
        }
        return agencyMapper.toResponse(saved);
    }

    /**
     * Deactivates an agency by setting its status to {@link AgencyStatus#INACTIVE}.
     * The row is never deleted.
     *
     * <p>An agency that is already inactive is left untouched and produces no audit event:
     * the audit trail only records effective mutations.</p>
     */
    @Transactional
    public AgencyResponse deactivate(UUID id) {
        Agency agency = findAgencyOrThrow(id);
        AgencyStatus previousStatus = agency.getStatus();
        if (previousStatus == AgencyStatus.INACTIVE) {
            return agencyMapper.toResponse(agency);
        }
        agency.setStatus(AgencyStatus.INACTIVE);
        Agency saved = agencyRepository.save(agency);
        auditLogService.record(resolveActorUserId(), ACTION_AGENCY_DEACTIVATED, AUDIT_ENTITY_AGENCY,
                entityIdOf(saved.getId()), AuditOutcome.SUCCESS,
                Map.of("previousStatus", previousStatus.name(), "status", saved.getStatus().name()));
        return agencyMapper.toResponse(saved);
    }

    /**
     * Reactivates an agency by setting its status to {@link AgencyStatus#ACTIVE}.
     * The row is never created nor deleted, and no other field is touched.
     *
     * <p>An agency that is already active is left untouched and produces no audit event:
     * the audit trail only records effective mutations.</p>
     */
    @Transactional
    public AgencyResponse activate(UUID id) {
        Agency agency = findAgencyOrThrow(id);
        AgencyStatus previousStatus = agency.getStatus();
        if (previousStatus == AgencyStatus.ACTIVE) {
            return agencyMapper.toResponse(agency);
        }
        agency.setStatus(AgencyStatus.ACTIVE);
        Agency saved = agencyRepository.save(agency);
        auditLogService.record(resolveActorUserId(), ACTION_AGENCY_ACTIVATED, AUDIT_ENTITY_AGENCY,
                entityIdOf(saved.getId()), AuditOutcome.SUCCESS,
                Map.of("previousStatus", previousStatus.name(), "status", saved.getStatus().name()));
        return agencyMapper.toResponse(saved);
    }

    private Agency findAgencyOrThrow(UUID id) {
        return agencyRepository.findById(id)
                .orElseThrow(() -> new AgencyNotFoundException(id));
    }

    private UUID resolveActorUserId() {
        return currentActorResolver.resolveCurrentActorUserId();
    }

    /**
     * Converts a local identifier into its audit string form. The identifier is always
     * present in production (the JPA lifecycle assigns it on save), but a null is tolerated
     * so a missing identifier can never break the audited mutation itself.
     */
    private static String entityIdOf(UUID id) {
        return id == null ? null : id.toString();
    }

    private void ensureCodeAvailable(String code, UUID excludedId) {
        boolean alreadyUsed = excludedId == null
                ? agencyRepository.existsByCode(code)
                : agencyRepository.existsByCodeAndIdNot(code, excludedId);
        if (alreadyUsed) {
            throw new AgencyCodeAlreadyUsedException(code);
        }
    }

    private CreateAgencyRequest normalize(CreateAgencyRequest request) {
        return new CreateAgencyRequest(normalizeCode(request.code()), normalizeName(request.name()));
    }

    private UpdateAgencyRequest normalize(UpdateAgencyRequest request) {
        return new UpdateAgencyRequest(normalizeCode(request.code()), normalizeName(request.name()));
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
