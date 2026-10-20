package com.sdigital.platform.function.service;

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

    private final FunctionRepository functionRepository;
    private final FunctionMapper functionMapper;

    public FunctionService(FunctionRepository functionRepository, FunctionMapper functionMapper) {
        this.functionRepository = functionRepository;
        this.functionMapper = functionMapper;
    }

    @Transactional
    public FunctionResponse create(CreateFunctionRequest request) {
        CreateFunctionRequest normalized = normalize(request);
        ensureCodeAvailable(normalized.code(), null);
        Function function = functionMapper.toEntity(normalized);
        return functionMapper.toResponse(functionRepository.save(function));
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
        functionMapper.applyUpdate(function, normalized);
        return functionMapper.toResponse(functionRepository.save(function));
    }

    /**
     * Deactivates a function by setting its status to {@link FunctionStatus#INACTIVE}.
     * The row is never deleted.
     */
    @Transactional
    public FunctionResponse deactivate(UUID id) {
        Function function = findFunctionOrThrow(id);
        function.setStatus(FunctionStatus.INACTIVE);
        return functionMapper.toResponse(functionRepository.save(function));
    }

    /**
     * Reactivates a function by setting its status to {@link FunctionStatus#ACTIVE}.
     * The row is never created nor deleted, and no other field is touched.
     */
    @Transactional
    public FunctionResponse activate(UUID id) {
        Function function = findFunctionOrThrow(id);
        function.setStatus(FunctionStatus.ACTIVE);
        return functionMapper.toResponse(functionRepository.save(function));
    }

    private Function findFunctionOrThrow(UUID id) {
        return functionRepository.findById(id)
                .orElseThrow(() -> new FunctionNotFoundException(id));
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
