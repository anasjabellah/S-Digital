package com.sdigital.platform.agency.mapper;

import com.sdigital.platform.agency.dto.AgencyResponse;
import com.sdigital.platform.agency.dto.CreateAgencyRequest;
import com.sdigital.platform.agency.dto.UpdateAgencyRequest;
import com.sdigital.platform.agency.entity.Agency;
import org.springframework.stereotype.Component;

/**
 * Manual mapper between the {@link Agency} entity and its DTOs.
 *
 * <p>Pure translation only: no persistence access and no business logic (code
 * normalization and uniqueness checks belong to the service).</p>
 */
@Component
public class AgencyMapper {

    public AgencyResponse toResponse(Agency agency) {
        return new AgencyResponse(
                agency.getId(),
                agency.getCode(),
                agency.getName(),
                agency.getStatus(),
                agency.getCreatedAt(),
                agency.getUpdatedAt()
        );
    }

    public Agency toEntity(CreateAgencyRequest request) {
        return new Agency(request.code(), request.name());
    }

    public void applyUpdate(Agency agency, UpdateAgencyRequest request) {
        agency.setCode(request.code());
        agency.setName(request.name());
    }
}
