package com.sdigital.platform.function.service;

import com.sdigital.platform.audit.entity.AuditOutcome;
import com.sdigital.platform.audit.service.AuditChanges;
import com.sdigital.platform.audit.service.AuditLogService;
import com.sdigital.platform.common.security.CurrentActorResolver;
import com.sdigital.platform.function.dto.CreateFunctionRequest;
import com.sdigital.platform.function.dto.FunctionResponse;
import com.sdigital.platform.function.dto.UpdateFunctionRequest;
import com.sdigital.platform.function.entity.Function;
import com.sdigital.platform.function.entity.FunctionStatus;
import com.sdigital.platform.function.exception.FunctionCodeAlreadyUsedException;
import com.sdigital.platform.function.exception.FunctionNotFoundException;
import com.sdigital.platform.function.mapper.FunctionMapper;
import com.sdigital.platform.function.repository.FunctionRepository;
import com.sdigital.platform.function.repository.FunctionSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Business operations for the function module.
 *
 * <p>Responsibilities: code normalization (trim + uppercase), name trimming, uniqueness
 * enforcement and status transitions. Persistence stays in the repository, mapping stays
 * in the mapper.</p>
 */
@Service
public class FunctionService {

    private static final String AUDIT_ENTITY_FUNCTION = "FUNCTION";

    private static final String ACTION_FUNCTION_CREATED = "FUNCTION_CREATED";
    private static final String ACTION_FUNCTION_UPDATED = "FUNCTION_UPDATED";
    private static final String ACTION_FUNCTION_DEACTIVATED = "FUNCTION_DEACTIVATED";
    private static final String ACTION_FUNCTION_ACTIVATED = "FUNCTION_ACTIVATED";

    private final FunctionRepository functionRepository;
    private final FunctionMapper functionMapper;
    private final AuditLogService auditLogService;
    private final CurrentActorResolver currentActorResolver;

    public FunctionService(FunctionRepository functionRepository,
                           FunctionMapper functionMapper,
                           AuditLogService auditLogService,
                           CurrentActorResolver currentActorResolver) {
        this.functionRepository = functionRepository;
        this.functionMapper = functionMapper;
        this.auditLogService = auditLogService;
        this.currentActorResolver = currentActorResolver;
    }

    @Transactional
    public FunctionResponse create(CreateFunctionRequest request) {
        CreateFunctionRequest normalized = normalize(request);
        ensureCodeAvailable(normalized.code(), null);
        Function function = functionMapper.toEntity(normalized);
        Function saved = functionRepository.save(function);
        auditLogService.record(resolveActorUserId(), ACTION_FUNCTION_CREATED, AUDIT_ENTITY_FUNCTION,
                entityIdOf(saved.getId()), AuditOutcome.SUCCESS, Map.of("status", saved.getStatus().name()));
        return functionMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public FunctionResponse getById(UUID id) {
        return functionMapper.toResponse(findFunctionOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Page<FunctionResponse> getAll(Pageable pageable) {
        return functionRepository.findAll(pageable).map(functionMapper::toResponse);
    }

    /**
     * Returns a page of functions narrowed by optional filters. A blank search is treated
     * as absent; when both filters are absent the historical unfiltered listing is used.
     */
    @Transactional(readOnly = true)
    public Page<FunctionResponse> getAll(String search, FunctionStatus status, Pageable pageable) {
        if (isBlank(search) && status == null) {
            return getAll(pageable);
        }
        return functionRepository.findAll(FunctionSpecifications.withFilters(search, status), pageable)
                .map(functionMapper::toResponse);
    }

    @Transactional
    public FunctionResponse update(UUID id, UpdateFunctionRequest request) {
        Function function = findFunctionOrThrow(id);
        UpdateFunctionRequest normalized = normalize(request);
        ensureCodeAvailable(normalized.code(), id);
        AuditChanges changes = new AuditChanges()
                .track("code", function.getCode(), normalized.code())
                .track("name", function.getName(), normalized.name());
        functionMapper.applyUpdate(function, normalized);
        Function saved = functionRepository.save(function);
        if (!changes.isEmpty()) {
            auditLogService.record(resolveActorUserId(), ACTION_FUNCTION_UPDATED, AUDIT_ENTITY_FUNCTION,
                    entityIdOf(saved.getId()), AuditOutcome.SUCCESS, Map.of("fields", changes.fields()));
        }
        return functionMapper.toResponse(saved);
    }

    /**
     * Deactivates a function by setting its status to {@link FunctionStatus#INACTIVE}.
     * The row is never deleted.
     *
     * <p>A function that is already inactive is left untouched and produces no audit event:
     * the audit trail only records effective mutations.</p>
     */
    @Transactional
    public FunctionResponse deactivate(UUID id) {
        Function function = findFunctionOrThrow(id);
        FunctionStatus previousStatus = function.getStatus();
        if (previousStatus == FunctionStatus.INACTIVE) {
            return functionMapper.toResponse(function);
        }
        function.setStatus(FunctionStatus.INACTIVE);
        Function saved = functionRepository.save(function);
        auditLogService.record(resolveActorUserId(), ACTION_FUNCTION_DEACTIVATED, AUDIT_ENTITY_FUNCTION,
                entityIdOf(saved.getId()), AuditOutcome.SUCCESS,
                Map.of("previousStatus", previousStatus.name(), "status", saved.getStatus().name()));
        return functionMapper.toResponse(saved);
    }

    /**
     * Reactivates a function by setting its status to {@link FunctionStatus#ACTIVE}.
     * The row is never created nor deleted, and no other field is touched.
     *
     * <p>A function that is already active is left untouched and produces no audit event:
     * the audit trail only records effective mutations.</p>
     */
    @Transactional
    public FunctionResponse activate(UUID id) {
        Function function = findFunctionOrThrow(id);
        FunctionStatus previousStatus = function.getStatus();
        if (previousStatus == FunctionStatus.ACTIVE) {
            return functionMapper.toResponse(function);
        }
        function.setStatus(FunctionStatus.ACTIVE);
        Function saved = functionRepository.save(function);
        auditLogService.record(resolveActorUserId(), ACTION_FUNCTION_ACTIVATED, AUDIT_ENTITY_FUNCTION,
                entityIdOf(saved.getId()), AuditOutcome.SUCCESS,
                Map.of("previousStatus", previousStatus.name(), "status", saved.getStatus().name()));
        return functionMapper.toResponse(saved);
    }

    private Function findFunctionOrThrow(UUID id) {
        return functionRepository.findById(id)
                .orElseThrow(() -> new FunctionNotFoundException(id));
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
                ? functionRepository.existsByCode(code)
                : functionRepository.existsByCodeAndIdNot(code, excludedId);
        if (alreadyUsed) {
            throw new FunctionCodeAlreadyUsedException(code);
        }
    }

    private CreateFunctionRequest normalize(CreateFunctionRequest request) {
        return new CreateFunctionRequest(normalizeCode(request.code()), normalizeName(request.name()));
    }

    private UpdateFunctionRequest normalize(UpdateFunctionRequest request) {
        return new UpdateFunctionRequest(normalizeCode(request.code()), normalizeName(request.name()));
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
