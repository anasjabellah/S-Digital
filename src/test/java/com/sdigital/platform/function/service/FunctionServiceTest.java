package com.sdigital.platform.function.service;

import com.sdigital.platform.audit.entity.AuditOutcome;
import com.sdigital.platform.audit.service.AuditLogService;
import com.sdigital.platform.common.security.CurrentActorResolver;
import com.sdigital.platform.function.FunctionTestData;
import com.sdigital.platform.function.dto.CreateFunctionRequest;
import com.sdigital.platform.function.dto.FunctionResponse;
import com.sdigital.platform.function.dto.UpdateFunctionRequest;
import com.sdigital.platform.function.entity.Function;
import com.sdigital.platform.function.entity.FunctionStatus;
import com.sdigital.platform.function.exception.FunctionCodeAlreadyUsedException;
import com.sdigital.platform.function.exception.FunctionNotFoundException;
import com.sdigital.platform.function.mapper.FunctionMapper;
import com.sdigital.platform.function.repository.FunctionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FunctionService}. The repository is mocked; the mapper is the real
 * implementation so mapping is exercised together with the business rules.
 */
@ExtendWith(MockitoExtension.class)
class FunctionServiceTest {

    private static final UUID ACTOR_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private FunctionRepository functionRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CurrentActorResolver currentActorResolver;

    private FunctionService functionService;

    @BeforeEach
    void setUp() {
        functionService = new FunctionService(functionRepository, new FunctionMapper(), auditLogService, currentActorResolver);
    }

    @Test
    void createNormalizesCodeAndNameAndPersists() {
        CreateFunctionRequest request = new CreateFunctionRequest("  f-inge-01 ", "  Engineer 01 ");
        when(functionRepository.existsByCode("F-INGE-01")).thenReturn(false);
        when(functionRepository.save(any(Function.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FunctionResponse response = functionService.create(request);

        assertThat(response.code()).isEqualTo("F-INGE-01");
        assertThat(response.name()).isEqualTo("Engineer 01");
        assertThat(response.status()).isEqualTo(FunctionStatus.ACTIVE);
        verify(functionRepository).existsByCode("F-INGE-01");
        verify(functionRepository).save(any(Function.class));
    }

    @Test
    void createRejectsAlreadyUsedCode() {
        CreateFunctionRequest request = new CreateFunctionRequest("F-INGE-01", "Engineer 01");
        when(functionRepository.existsByCode("F-INGE-01")).thenReturn(true);

        assertThatThrownBy(() -> functionService.create(request))
                .isInstanceOf(FunctionCodeAlreadyUsedException.class);

        verify(functionRepository, never()).save(any(Function.class));
    }

    @Test
    void getByIdThrowsWhenFunctionDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(functionRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> functionService.getById(id))
                .isInstanceOf(FunctionNotFoundException.class);
    }

    @Test
    void getByIdReturnsMappedFunction() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-01-02T10:00:00Z");
        Function function = FunctionTestData.function(id, "F-INGE-01", "Engineer 01", createdAt, updatedAt);
        when(functionRepository.findById(id)).thenReturn(Optional.of(function));

        FunctionResponse response = functionService.getById(id);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.code()).isEqualTo("F-INGE-01");
        assertThat(response.status()).isEqualTo(FunctionStatus.ACTIVE);
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void updateAllowsUnchangedCodeOfSameFunction() {
        UUID id = UUID.randomUUID();
        Function function = FunctionTestData.function(id, "F-INGE-01", "Engineer 01", Instant.now(), Instant.now());
        UpdateFunctionRequest request = new UpdateFunctionRequest("  f-inge-01 ", "  Senior Engineer ");
        when(functionRepository.findById(id)).thenReturn(Optional.of(function));
        when(functionRepository.existsByCodeAndIdNot("F-INGE-01", id)).thenReturn(false);
        when(functionRepository.save(any(Function.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FunctionResponse response = functionService.update(id, request);

        assertThat(response.code()).isEqualTo("F-INGE-01");
        assertThat(response.name()).isEqualTo("Senior Engineer");
        verify(functionRepository).existsByCodeAndIdNot("F-INGE-01", id);
        verify(functionRepository).save(function);
    }

    @Test
    void updateRejectsCodeUsedByAnotherFunction() {
        UUID id = UUID.randomUUID();
        Function function = FunctionTestData.function(id, "F-INGE-01", "Engineer 01", Instant.now(), Instant.now());
        UpdateFunctionRequest request = new UpdateFunctionRequest("F-OPS-01", "Engineer 01");
        when(functionRepository.findById(id)).thenReturn(Optional.of(function));
        when(functionRepository.existsByCodeAndIdNot("F-OPS-01", id)).thenReturn(true);

        assertThatThrownBy(() -> functionService.update(id, request))
                .isInstanceOf(FunctionCodeAlreadyUsedException.class);

        verify(functionRepository, never()).save(any(Function.class));
    }

    @Test
    void updateDoesNotModifyProtectedFields() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-01-02T10:00:00Z");
        Function function = FunctionTestData.function(id, "F-INGE-01", "Engineer 01", createdAt, updatedAt);
        UpdateFunctionRequest request = new UpdateFunctionRequest("F-INGE-01", "Senior Engineer");
        when(functionRepository.findById(id)).thenReturn(Optional.of(function));
        when(functionRepository.existsByCodeAndIdNot("F-INGE-01", id)).thenReturn(false);
        when(functionRepository.save(any(Function.class))).thenAnswer(invocation -> invocation.getArgument(0));

        functionService.update(id, request);

        assertThat(function.getId()).isEqualTo(id);
        assertThat(function.getCreatedAt()).isEqualTo(createdAt);
        assertThat(function.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(function.getStatus()).isEqualTo(FunctionStatus.ACTIVE);
    }

    @Test
    void deactivateSetsStatusInactiveWithoutDeleting() {
        UUID id = UUID.randomUUID();
        Function function = FunctionTestData.function(id, "F-INGE-01", "Engineer 01", Instant.now(), Instant.now());
        when(functionRepository.findById(id)).thenReturn(Optional.of(function));
        when(functionRepository.save(any(Function.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FunctionResponse response = functionService.deactivate(id);

        assertThat(response.status()).isEqualTo(FunctionStatus.INACTIVE);
        verify(functionRepository).save(function);
        verify(functionRepository, never()).deleteById(any(UUID.class));
    }

    @Test
    void activateRestoresStatusActiveAndPreservesOtherFields() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-01-02T10:00:00Z");
        Function function = FunctionTestData.function(id, "F-INGE-01", "Engineer 01", createdAt, updatedAt);
        function.setStatus(FunctionStatus.INACTIVE);
        when(functionRepository.findById(id)).thenReturn(Optional.of(function));
        when(functionRepository.save(any(Function.class))).thenAnswer(invocation -> invocation.getArgument(0));

        FunctionResponse response = functionService.activate(id);

        assertThat(response.status()).isEqualTo(FunctionStatus.ACTIVE);
        assertThat(response.id()).isEqualTo(id);
        assertThat(response.code()).isEqualTo("F-INGE-01");
        assertThat(response.name()).isEqualTo("Engineer 01");
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.updatedAt()).isEqualTo(updatedAt);
        verify(functionRepository).save(function);
        verify(functionRepository, never()).deleteById(any(UUID.class));
    }

    @Test
    void activateThrowsWhenFunctionDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(functionRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> functionService.activate(id))
                .isInstanceOf(FunctionNotFoundException.class);

        verify(functionRepository, never()).save(any(Function.class));
    }

    @Test
    void getAllReturnsMappedPage() {
        Function function = FunctionTestData.function(UUID.randomUUID(), "F-INGE-01", "Engineer 01",
                Instant.now(), Instant.now());
        Pageable pageable = PageRequest.of(0, 10);
        when(functionRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(function), pageable, 1));

        Page<FunctionResponse> page = functionService.getAll(pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).code()).isEqualTo("F-INGE-01");
    }

    @Test
    void getAllWithoutFiltersKeepsTheUnfilteredQuery() {
        Pageable pageable = PageRequest.of(0, 10);
        when(functionRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        functionService.getAll(null, null, pageable);

        verify(functionRepository).findAll(pageable);
        verify(functionRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void getAllTreatsBlankSearchAsAbsent() {
        Pageable pageable = PageRequest.of(0, 10);
        when(functionRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        functionService.getAll("   ", null, pageable);

        verify(functionRepository).findAll(pageable);
        verify(functionRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void getAllWithSearchUsesTheFilteredQuery() {
        Pageable pageable = PageRequest.of(0, 10);
        Function function = FunctionTestData.function(UUID.randomUUID(), "F-INGE-01", "Engineer 01",
                Instant.now(), Instant.now());
        when(functionRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(function), pageable, 1));

        Page<FunctionResponse> page = functionService.getAll("engineer", null, pageable);

        assertThat(page.getContent()).hasSize(1);
        verify(functionRepository, never()).findAll(pageable);
    }

    @Test
    void getAllWithStatusOnlyUsesTheFilteredQuery() {
        Pageable pageable = PageRequest.of(0, 10);
        when(functionRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        functionService.getAll(null, FunctionStatus.INACTIVE, pageable);

        verify(functionRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    void getAllCombinesSearchAndStatus() {
        Pageable pageable = PageRequest.of(0, 10);
        when(functionRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        functionService.getAll("  engineer ", FunctionStatus.ACTIVE, pageable);

        verify(functionRepository).findAll(any(Specification.class), eq(pageable));
        verify(functionRepository, never()).findAll(pageable);
    }

    // --- persistent audit ----------------------------------------------------------------------

    @Test
    void createAuditsFunctionCreated() {
        UUID functionId = UUID.randomUUID();
        when(functionRepository.existsByCode("F-INGE-01")).thenReturn(false);
        when(functionRepository.save(any(Function.class))).thenAnswer(invocation -> {
            Function saved = invocation.getArgument(0);
            FunctionTestData.setId(saved, functionId);
            return saved;
        });
        when(currentActorResolver.resolveCurrentActorUserId()).thenReturn(ACTOR_ID);

        functionService.create(new CreateFunctionRequest("F-INGE-01", "Engineer 01"));

        verify(auditLogService).record(eq(ACTOR_ID), eq("FUNCTION_CREATED"), eq("FUNCTION"),
                eq(functionId.toString()), eq(AuditOutcome.SUCCESS), anyMap());
    }

    @Test
    void updateAuditsFunctionUpdated() {
        UUID functionId = UUID.randomUUID();
        when(functionRepository.findById(functionId)).thenReturn(Optional.of(
                FunctionTestData.function(functionId, "F-INGE-01", "Engineer 01", Instant.now(), Instant.now())));
        when(functionRepository.existsByCodeAndIdNot("F-INGE-02", functionId)).thenReturn(false);
        when(functionRepository.save(any(Function.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(currentActorResolver.resolveCurrentActorUserId()).thenReturn(ACTOR_ID);

        functionService.update(functionId, new UpdateFunctionRequest("F-INGE-02", "Engineer 02"));

        verify(auditLogService).record(eq(ACTOR_ID), eq("FUNCTION_UPDATED"), eq("FUNCTION"),
                eq(functionId.toString()), eq(AuditOutcome.SUCCESS),
                eq(Map.of("fields", List.of("code", "name"))));
    }

    @Test
    void updateAuditsOnlyTheFieldsThatReallyChanged() {
        UUID functionId = UUID.randomUUID();
        when(functionRepository.findById(functionId)).thenReturn(Optional.of(
                FunctionTestData.function(functionId, "F-INGE-01", "Engineer 01", Instant.now(), Instant.now())));
        when(functionRepository.existsByCodeAndIdNot("F-INGE-01", functionId)).thenReturn(false);
        when(functionRepository.save(any(Function.class))).thenAnswer(invocation -> invocation.getArgument(0));

        functionService.update(functionId, new UpdateFunctionRequest("F-INGE-01", "Engineer 02"));

        verify(auditLogService).record(any(), eq("FUNCTION_UPDATED"), eq("FUNCTION"),
                eq(functionId.toString()), eq(AuditOutcome.SUCCESS),
                eq(Map.of("fields", List.of("name"))));
    }

    @Test
    void updateThatChangesNothingWritesNoAuditEvent() {
        UUID functionId = UUID.randomUUID();
        when(functionRepository.findById(functionId)).thenReturn(Optional.of(
                FunctionTestData.function(functionId, "F-INGE-01", "Engineer 01", Instant.now(), Instant.now())));
        when(functionRepository.existsByCodeAndIdNot("F-INGE-01", functionId)).thenReturn(false);
        when(functionRepository.save(any(Function.class))).thenAnswer(invocation -> invocation.getArgument(0));

        functionService.update(functionId, new UpdateFunctionRequest("F-INGE-01", "Engineer 01"));

        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    void deactivateAuditsFunctionDeactivatedWithStatusTransition() {
        UUID functionId = UUID.randomUUID();
        when(functionRepository.findById(functionId)).thenReturn(Optional.of(
                FunctionTestData.function(functionId, "F-INGE-01", "Engineer 01", Instant.now(), Instant.now())));
        when(functionRepository.save(any(Function.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(currentActorResolver.resolveCurrentActorUserId()).thenReturn(ACTOR_ID);

        functionService.deactivate(functionId);

        verify(auditLogService).record(eq(ACTOR_ID), eq("FUNCTION_DEACTIVATED"), eq("FUNCTION"),
                eq(functionId.toString()), eq(AuditOutcome.SUCCESS),
                eq(Map.of("previousStatus", "ACTIVE", "status", "INACTIVE")));
    }

    @Test
    void deactivateOnAlreadyInactiveFunctionWritesNoAuditEvent() {
        UUID functionId = UUID.randomUUID();
        Function function = FunctionTestData.function(functionId, "F-INGE-01", "Engineer 01", Instant.now(), Instant.now());
        function.setStatus(FunctionStatus.INACTIVE);
        when(functionRepository.findById(functionId)).thenReturn(Optional.of(function));

        functionService.deactivate(functionId);

        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), anyMap());
        verify(functionRepository, never()).save(any(Function.class));
    }

    @Test
    void activateAuditsFunctionActivatedWithStatusTransition() {
        UUID functionId = UUID.randomUUID();
        Function function = FunctionTestData.function(functionId, "F-INGE-01", "Engineer 01", Instant.now(), Instant.now());
        function.setStatus(FunctionStatus.INACTIVE);
        when(functionRepository.findById(functionId)).thenReturn(Optional.of(function));
        when(functionRepository.save(any(Function.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(currentActorResolver.resolveCurrentActorUserId()).thenReturn(null);

        functionService.activate(functionId);

        verify(auditLogService).record(eq(null), eq("FUNCTION_ACTIVATED"), eq("FUNCTION"),
                eq(functionId.toString()), eq(AuditOutcome.SUCCESS),
                eq(Map.of("previousStatus", "INACTIVE", "status", "ACTIVE")));
    }

    @Test
    void activateOnAlreadyActiveFunctionWritesNoAuditEvent() {
        UUID functionId = UUID.randomUUID();
        when(functionRepository.findById(functionId)).thenReturn(Optional.of(
                FunctionTestData.function(functionId, "F-INGE-01", "Engineer 01", Instant.now(), Instant.now())));

        functionService.activate(functionId);

        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), anyMap());
        verify(functionRepository, never()).save(any(Function.class));
    }

    @Test
    void failedMutationWritesNoAuditEvent() {
        when(functionRepository.existsByCode("F-INGE-01")).thenReturn(true);

        assertThatThrownBy(() -> functionService.create(new CreateFunctionRequest("F-INGE-01", "Engineer 01")))
                .isInstanceOf(FunctionCodeAlreadyUsedException.class);

        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), anyMap());
    }

    @Test
    void readOperationsWriteNoAuditEvent() {
        Pageable pageable = PageRequest.of(0, 10);
        when(functionRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        functionService.getAll(pageable);

        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), anyMap());
    }
}
