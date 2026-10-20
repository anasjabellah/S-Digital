package com.sdigital.platform.function.mapper;

import com.sdigital.platform.function.dto.CreateFunctionRequest;
import com.sdigital.platform.function.dto.FunctionResponse;
import com.sdigital.platform.function.dto.UpdateFunctionRequest;
import com.sdigital.platform.function.entity.Function;
import org.springframework.stereotype.Component;

/**
 * Manual mapper between the {@link Function} entity and its DTOs.
 *
 * <p>Pure translation only: no persistence access and no business logic (code
 * normalization and uniqueness checks belong to the service).</p>
 */
@Component
public class FunctionMapper {

    public FunctionResponse toResponse(Function function) {
        return new FunctionResponse(
                function.getId(),
                function.getCode(),
                function.getName(),
                function.getStatus(),
                function.getCreatedAt(),
                function.getUpdatedAt()
        );
    }

    public Function toEntity(CreateFunctionRequest request) {
        return new Function(request.code(), request.name());
    }

    public void applyUpdate(Function function, UpdateFunctionRequest request) {
        function.setCode(request.code());
        function.setName(request.name());
    }
}
