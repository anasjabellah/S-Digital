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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
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

    private final AgencyRepository agencyRepository;
    private final AgencyMapper agencyMapper;

    public AgencyService(AgencyRepository agencyRepository, AgencyMapper agencyMapper) {
        this.agencyRepository = agencyRepository;
        this.agencyMapper = agencyMapper;
    }

    @Transactional
    public AgencyResponse create(CreateAgencyRequest request) {
        CreateAgencyRequest normalized = normalize(request);
        ensureCodeAvailable(normalized.code(), null);
        Agency agency = agencyMapper.toEntity(normalized);
        return agencyMapper.toResponse(agencyRepository.save(agency));
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
        agencyMapper.applyUpdate(agency, normalized);
        return agencyMapper.toResponse(agencyRepository.save(agency));
    }

    /**
     * Deactivates an agency by setting its status to {@link AgencyStatus#INACTIVE}.
     * The row is never deleted.
     */
    @Transactional
    public AgencyResponse deactivate(UUID id) {
        Agency agency = findAgencyOrThrow(id);
        agency.setStatus(AgencyStatus.INACTIVE);
        return agencyMapper.toResponse(agencyRepository.save(agency));
    }

    /**
     * Reactivates an agency by setting its status to {@link AgencyStatus#ACTIVE}.
     * The row is never created nor deleted, and no other field is touched.
     */
    @Transactional
    public AgencyResponse activate(UUID id) {
        Agency agency = findAgencyOrThrow(id);
        agency.setStatus(AgencyStatus.ACTIVE);
        return agencyMapper.toResponse(agencyRepository.save(agency));
    }

    private Agency findAgencyOrThrow(UUID id) {
        return agencyRepository.findById(id)
                .orElseThrow(() -> new AgencyNotFoundException(id));
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
